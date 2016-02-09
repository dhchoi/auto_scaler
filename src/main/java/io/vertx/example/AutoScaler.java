package io.vertx.example;


import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.model.compute.Address;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ServerCreate;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.telemetry.SampleCriteria;
import org.openstack4j.model.telemetry.Statistics;
import org.openstack4j.openstack.OSFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AutoScaling server
 */
public class AutoScaler {

    /**
     * OpenStack4j configuration
     */
    private static OSClient os;
    private static List<Server> dataCenters = new CopyOnWriteArrayList<>();

    /**
     * ASG Variables
     */
    private static String ASG_IMAGE;
    private static String ASG_IMAGE_ID;
    private static String ASG_FLAVOR;
    private static String ASG_FLAVOR_ID;
    private static String ASG_NAME;
    private static String LB_IPADDR;
    private static int CPU_UPPER_TRES;
    private static int CPU_LOWER_TRES;
    private static int MIN_INSTANCE;
    private static int MAX_INSTANCE;
    private static long EVAL_PERIOD; // milliseconds
    private static int EVAL_COUNT;
    private static long COOLDOWN; // milliseconds
    private static int DELTA;
    private static int SCALE_OUT_HIT_CNT = 0;
    private static int SCALE_IN_HIT_CNT = 0;

    /**
     * Helper Variables
     */
    private static final String CHARSET = "UTF-8";
    private static Date startupTime = null;


    /**
     * Main function
     */
    public static void main(String[] args) {
        /*
         * read in parameters
         */
        try {
            System.out.println("/********** All Parameters **********/");
            System.out.println("ASG_IMAGE: " + (ASG_IMAGE = String.valueOf(System.getProperty("ASG_IMAGE"))));
            System.out.println("ASG_FLAVOR: " + (ASG_FLAVOR = String.valueOf(System.getProperty("ASG_FLAVOR"))));
            System.out.println("ASG_NAME: " + (ASG_NAME = String.valueOf(System.getProperty("ASG_NAME"))));
            System.out.println("LB_IPADDR: " + (LB_IPADDR = String.valueOf(System.getProperty("LB_IPADDR"))));
            System.out.println("CPU_UPPER_TRES: " + (CPU_UPPER_TRES = Integer.valueOf(System.getProperty("CPU_UPPER_TRES"))));
            System.out.println("CPU_LOWER_TRES: " + (CPU_LOWER_TRES = Integer.valueOf(System.getProperty("CPU_LOWER_TRES"))));
            System.out.println("MIN_INSTANCE: " + (MIN_INSTANCE = Integer.valueOf(System.getProperty("MIN_INSTANCE"))));
            System.out.println("MAX_INSTANCE: " + (MAX_INSTANCE = Integer.valueOf(System.getProperty("MAX_INSTANCE"))));
            System.out.println("EVAL_PERIOD: " + (EVAL_PERIOD = Long.valueOf(System.getProperty("EVAL_PERIOD")) * 1000));
            System.out.println("EVAL_COUNT: " + (EVAL_COUNT = Integer.valueOf(System.getProperty("EVAL_COUNT"))));
            System.out.println("COOLDOWN: " + (COOLDOWN = Long.valueOf(System.getProperty("COOLDOWN")) * 1000));
            System.out.println("DELTA: " + (DELTA = Integer.valueOf(System.getProperty("DELTA"))));
            System.out.println();
        } catch (NullPointerException exception) {
            System.out.println("Insufficient parameters. Please use the following command format:\n" +
                    "\tjava -DASG_IMAGE=$ASG_IMAGE -DASG_FLAVOR=$ASG_FLAVOR -DASG_NAME=$ASG_NAME \\\n" +
                    "\t-DLB_IPADDR=$LB_IPADDR -DCPU_UPPER_TRES=$CPU_UPPER_TRES -DCPU_LOWER_TRES=$CPU_LOWER_TRES \\\n" +
                    "\t-DMIN_INSTANCE=$MIN_INSTANCE -DMAX_INSTANCE=$MAX_INSTANCE \\\n" +
                    "\t-DEVAL_PERIOD=$EVAL_PERIOD -DEVAL_COUNT=$EVAL_COUNT \\\n" +
                    "\t-DCOOLDOWN=$COOLDOWN -DDELTA=$DELTA \\\n" +
                    "\t-jar target/cc-q-3-3.1.0-fat.jar");
            System.exit(0);
        } catch (NumberFormatException exception) {
            System.out.println("Ill-formatted parameters. Please check if the numbers were provided correctly.");
            System.exit(0);
        }
        startupTime = new Date();
        System.out.println("Program started at " + formatTime(startupTime));

        /*
         * Authenticate
         */
        authenticate();

        /*
         * Check Environment
         */
        // Find all Compute Flavors
        System.out.println("/********** All Flavors **********/");
        for (Flavor flavor : os.compute().flavors().list()) {
            System.out.println(flavor);
            if (flavor.getName().equals(ASG_FLAVOR)) {
                ASG_FLAVOR_ID = flavor.getId();
                System.out.println("Setting ASG_FLAVOR_ID=" + ASG_FLAVOR_ID + " for ASG_FLAVOR=" + ASG_FLAVOR);
            }
        }
        System.out.println();
        // List all Images (Glance)
        System.out.println("/********** All Images **********/");
        for (Image image : os.images().list()) {
            System.out.println(image);
            if (image.getName().equals(ASG_IMAGE)) {
                ASG_IMAGE_ID = image.getId();
                System.out.println("Setting ASG_IMAGE_ID=" + ASG_IMAGE_ID + " for ASG_IMAGE=" + ASG_IMAGE);
            }
        }

        /*
         * Launch data centers
         */
        launchDataCenters(MIN_INSTANCE);

        // list all running servers
        System.out.println("/********** All Running Instances **********/");
        for (Server server : os.compute().servers().list()) {
            System.out.println(server.getId());
            System.out.println(os.compute().servers().get(server.getId()).getStatus());
            System.out.println(getServerAddress(server));
        }
        System.out.println();

        System.out.println("/********** All Running Instances (DC) **********/");
        for (Server server : dataCenters) {
            System.out.println(server.getId());
            System.out.println(os.compute().servers().get(server.getId()).getStatus());
            System.out.println(getServerAddress(server));
        }

        /*
         * Start monitoring
         */
        startMonitoring();
    }

    /**
     * Authenticate user
     */
    private static void authenticate() {
        os = OSFactory.builder()
                .endpoint("http://127.0.0.1:5000/v2.0")
                .credentials("admin", "labstack")
                .tenantName("demo")
                .authenticate();
    }

    private static String getServerAddress(Server server) {
        String address = "";
        Map<String, List<? extends Address>> addresses = os.compute().servers().get(server.getId()).getAddresses().getAddresses();
        for (String key : addresses.keySet()) {
            address = addresses.get(key).get(0).getAddr();
        }

        return address;
    }

    /**
     * Launches specified number of Data Centers
     */
    private static void launchDataCenters(int num) {
        for (int i = 0; i < num; i++) {
            // create a Server Model Object
            ServerCreate sc = Builders.server().name(ASG_NAME).flavor(ASG_FLAVOR_ID).image(ASG_IMAGE_ID).build();
            // boot the Server
            Server dataCenter = os.compute().servers().boot(sc);
            dataCenters.add(dataCenter);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    authenticate();

                    while (os.compute().servers().get(dataCenter.getId()).getStatus() != Server.Status.ACTIVE) {
                        try {
                            System.out.println("[DC:Launch] Waiting for data center " + dataCenter.getId() + " to become active.");
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    System.out.println("[DC:Launch] Successfully activated data center " + dataCenter.getId());

                    System.out.println("[DC:Launch] Checking health state of data center " + dataCenter.getId());
                    while (!isHealthy(dataCenter)) {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    System.out.print("\n");

                    // notify load balancer
                    System.out.println("[DC:Launch] Notifying load balancer for " + dataCenter.getId());
                    Map<String, String> query = new HashMap<String, String>();
                    query.put("ip", getServerAddress(dataCenter));
                    httpRequest("http://" + LB_IPADDR + ":8080/add", query);
                }
            }).start();
        }
    }

    /**
     * Deletes specified number of Data Centers
     */
    private static void deleteDataCenters(int num) {
        for (int i = 0; i < num; i++) {
            // get the latest data center
            Server dataCenter = dataCenters.remove(dataCenters.size() - 1);
            System.out.println("[DC:Launch] Deleting data center " + dataCenter.getId());

            // delete the data center
            os.compute().servers().delete(dataCenter.getId());

            // notify load balancer
            Map<String, String> query = new HashMap<String, String>();
            query.put("ip", getServerAddress(dataCenter));
            httpRequest("http://" + LB_IPADDR + ":8080/remove", query);
        }
    }

    /**
     * Monitor metering
     */
    private static void startMonitoring() {
        new Thread(new Runnable() {
            private long sleepDuration = EVAL_PERIOD;

            @Override
            public void run() {
                authenticate();

                while (true) {
                    try {
                        System.out.println("[Monitor] Sleeping for " + sleepDuration + "ms...");
                        Thread.sleep(sleepDuration);

                        // build filter
                        SampleCriteria sampleCriteria = SampleCriteria.create().timestamp(SampleCriteria.Oper.GTE, startupTime);
                        // query statistics
                        List<? extends Statistics> stats = os.telemetry().meters().statistics("cpu_util", sampleCriteria, (int) sleepDuration / 1000);
                        // get query result
                        Statistics statistics = stats.get(stats.size() - 1); // most recent statistic during the interval
                        System.out.println("[Monitor] stats.get(stats.size()-1)=" + statistics);
                        double statAvg = statistics.getAvg();
                        System.out.println("[Monitor] cpu_util=" + statAvg);

                        if (statAvg > CPU_LOWER_TRES) { // scale out
                            System.out.println("[Monitor] stat is above CPU_LOWER_TRES=" + CPU_LOWER_TRES);
                            SCALE_IN_HIT_CNT = 0;
                            if (++SCALE_OUT_HIT_CNT >= EVAL_COUNT && dataCenters.size() < MAX_INSTANCE) {
                                System.out.println("[Monitor] scaling out with SCALE_OUT_HIT_CNT=" + SCALE_OUT_HIT_CNT);
                                launchDataCenters(DELTA);
                                SCALE_OUT_HIT_CNT = 0;
                                sleepDuration = COOLDOWN;
                            }
                        } else if (statAvg < CPU_UPPER_TRES) { // scale in
                            System.out.println("[Monitor] stat is below CPU_UPPER_TRES=" + CPU_UPPER_TRES);
                            SCALE_OUT_HIT_CNT = 0;
                            if (++SCALE_IN_HIT_CNT >= EVAL_COUNT && dataCenters.size() > MIN_INSTANCE) {
                                System.out.println("[Monitor] scaling in with SCALE_IN_HIT_CNT=" + SCALE_IN_HIT_CNT);
                                deleteDataCenters(DELTA);
                                SCALE_IN_HIT_CNT = 0;
                                sleepDuration = COOLDOWN;
                            }
                        } else {
                            SCALE_OUT_HIT_CNT = 0;
                            SCALE_IN_HIT_CNT = 0;
                            sleepDuration = EVAL_PERIOD;
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    /**
     * Sends out HTTP GET requests
     *
     * @param url
     * @param data
     * @return
     */
    private static String httpRequest(String url, Map<String, String> data) {
        HttpURLConnection conn = null;
        String response = null;
        try {
            String path = url + "?" + createQueryString(data);
            System.out.println("[HTTP] Sending request: " + path);

            conn = openConnection(path);
            if (conn.getResponseCode() == HttpsURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                response = "";
                String line;
                while ((line = in.readLine()) != null) {
                    response += line;
                }
                System.out.println("[HTTP] Request success with response: " + response);
            } else {
                System.out.println("[HTTP] Bad http response code: " + conn.getResponseCode());
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            conn.disconnect();
        }

        return response;
    }

    /**
     * Checks health status of instance.
     *
     * @return true if instance is healthy
     */
    public static boolean isHealthy(Server dataCenter) {
        String url = "http://" + getServerAddress(dataCenter);
        try {
            HttpURLConnection httpURLConnection = openConnection(url);
            httpURLConnection.connect();

            if (httpURLConnection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                return false;
            }

        } catch (IOException e) {
            System.out.println("[DC:Launch] Unhealthy data center " + dataCenter.getId() + " at " + url + " (" + e.getMessage() + ")");
            return false;
        }

        return true;
    }

    private static HttpURLConnection openConnection(String path) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(path).openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "text/plain");
            conn.setRequestProperty("Accept-Charset", CHARSET);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }

        return conn;
    }

    /**
     * Helper for creating query strings
     *
     * @param params
     * @return
     */
    private static String createQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }

        String paramsAsString = "";
        try {
            for (String key : params.keySet()) {
                if (!paramsAsString.isEmpty()) {
                    paramsAsString += "&";
                }
                paramsAsString += key + "=" + URLEncoder.encode(params.get(key), CHARSET);
            }
        } catch (UnsupportedEncodingException e) {
            System.out.println(e.getMessage());
        }

        return paramsAsString;
    }

    private static String formatTime(Date date) {
        DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }
}
