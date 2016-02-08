package io.vertx.example;


import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ServerCreate;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.telemetry.SampleCriteria;
import org.openstack4j.model.telemetry.Statistics;
import org.openstack4j.openstack.OSFactory;

import java.util.List;
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
            System.out.println(os.compute().servers().get(server.getId()).getAccessIPv4());
        }
        System.out.println();

        System.out.println("/********** All Running Instances (DC) **********/");
        for (Server server : dataCenters) {
            System.out.println(server.getId());
            System.out.println(os.compute().servers().get(server.getId()).getStatus());
            System.out.println(os.compute().servers().get(server.getId()).getAccessIPv4());
        }
    }

    private static void authenticate() {
        os = OSFactory.builder()
                .endpoint("http://127.0.0.1:5000/v2.0")
                .credentials("admin", "labstack")
                .tenantName("admin")
                .authenticate();
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
                            System.out.println("Waiting for data center " + dataCenter.getId() + " to become active.");
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    System.out.println("Successfully launched data center " + dataCenter.getId());
                    // TODO: add to load balancer
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
            // delete the data center
            os.compute().servers().delete(dataCenter.getId());
            // TODO: remove from load balancer
        }
    }

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

                        SampleCriteria sampleCriteria = SampleCriteria.create();
                        for (Server server : dataCenters) {
                            sampleCriteria.add("id", SampleCriteria.Oper.EQUALS, server.getId());
                        }

                        double statAvg = 0;
                        List<? extends Statistics> stats = os.telemetry().meters().statistics("cpu_util", sampleCriteria);
                        for (Statistics statistics : stats) {
                            System.out.println(statistics);
                            statAvg += statistics.getAvg();
                        }
                        statAvg = statAvg / stats.size();
                        System.out.println("[Monitor] cpu_util=" + statAvg);

                        if (statAvg > CPU_UPPER_TRES) {
                            System.out.println("[Monitor] stat is above CPU_UPPER_TRES=" + CPU_UPPER_TRES);
                            SCALE_IN_HIT_CNT = 0;
                            if (++SCALE_OUT_HIT_CNT >= EVAL_COUNT && dataCenters.size() < MAX_INSTANCE) {
                                System.out.println("[Monitor] scaling out with SCALE_OUT_HIT_CNT=" + SCALE_OUT_HIT_CNT);
                                launchDataCenters(DELTA);
                                SCALE_OUT_HIT_CNT = 0;
                                sleepDuration = COOLDOWN;
                            }
                        } else if (statAvg < CPU_LOWER_TRES) {
                            System.out.println("[Monitor] stat is below CPU_LOWER_TRES=" + CPU_LOWER_TRES);
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
}
