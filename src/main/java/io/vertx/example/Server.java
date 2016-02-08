package io.vertx.example;


import org.openstack4j.api.OSClient;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.openstack.OSFactory;

import java.util.List;

/**
 * AutoScaling server
 */
public class Server {

    /**
     * OpenStack4j configuration
     */
    private static OSClient os;

    /**
     * ASG Variables
     */
    private static String ASG_IMAGE;
    private static String ASG_FLAVOR;
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
            System.out.println(ASG_IMAGE = String.valueOf(System.getProperty("ASG_IMAGE")));
            System.out.println(ASG_FLAVOR = String.valueOf(System.getProperty("ASG_FLAVOR")));
            System.out.println(ASG_NAME = String.valueOf(System.getProperty("ASG_NAME")));
            System.out.println(LB_IPADDR = String.valueOf(System.getProperty("LB_IPADDR")));
            System.out.println(CPU_UPPER_TRES = Integer.valueOf(System.getProperty("CPU_UPPER_TRES")));
            System.out.println(CPU_LOWER_TRES = Integer.valueOf(System.getProperty("CPU_LOWER_TRES")));
            System.out.println(MIN_INSTANCE = Integer.valueOf(System.getProperty("MIN_INSTANCE")));
            System.out.println(MAX_INSTANCE = Integer.valueOf(System.getProperty("MAX_INSTANCE")));
            System.out.println(EVAL_PERIOD = Long.valueOf(System.getProperty("EVAL_PERIOD")) * 1000);
            System.out.println(EVAL_COUNT = Integer.valueOf(System.getProperty("EVAL_COUNT")));
            System.out.println(COOLDOWN = Long.valueOf(System.getProperty("COOLDOWN")) * 1000);
            System.out.println(DELTA = Integer.valueOf(System.getProperty("DELTA")));
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
                .endpoint("http://172.30.0.183:5000/")
                .credentials("admin", "labstack")
                .tenantName("admin")
                .authenticate();

        /*
         * Test
         */
        List<? extends Flavor> flavors = os.compute().flavors().list();
        for (Flavor flavor : flavors) {
            System.out.println(flavor);
        }
    }
}
