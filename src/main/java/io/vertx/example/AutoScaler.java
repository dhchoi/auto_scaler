package io.vertx.example;


import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.ServerCreate;
import org.openstack4j.model.image.Image;
import org.openstack4j.openstack.OSFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoScaling server
 */
public class AutoScaler {

    /**
     * OpenStack4j configuration
     */
    private static OSClient os;
    private static List<Server> dataCenters = new ArrayList<>();

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
        os = OSFactory.builder()
                .endpoint("http://127.0.0.1:5000/v2.0")
                .credentials("admin", "labstack")
                .tenantName("admin")
                .authenticate();

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
        for (int i = 0; i < MIN_INSTANCE; i++) {
            Server dataCenter = launchDataCenter();
            System.out.print("Waiting for data center to become active.");
            while (dataCenter.getStatus() != Server.Status.ACTIVE) {
                try {
                    Thread.sleep(3000);
                    System.out.print(".");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println();
            dataCenters.add(launchDataCenter());
        }

        // list all running servers
        System.out.println("/********** All Running Instances **********/");
        for (Server server : os.compute().servers().list()) {
            System.out.println(server.getId());
            System.out.println(server.getStatus());
            System.out.println(server.getAccessIPv4());
        }
        System.out.println();

        System.out.println("/********** All Running Instances (DC) **********/");
        for (Server server : dataCenters) {
            System.out.println(server.getId());
            System.out.println(server.getStatus());
            System.out.println(server.getAccessIPv4());
        }
    }

    /**
     * Launches a Data Center
     *
     * @return the launched DC
     */
    private static Server launchDataCenter() {
        // Create a Server Model Object
        ServerCreate sc = Builders.server().name(ASG_NAME).flavor(ASG_FLAVOR_ID).image(ASG_IMAGE_ID).build();

        // Boot the Server
        return os.compute().servers().boot(sc);
    }
}
