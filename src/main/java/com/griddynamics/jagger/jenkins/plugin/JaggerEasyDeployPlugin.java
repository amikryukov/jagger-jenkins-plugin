package com.griddynamics.jagger.jenkins.plugin;

import com.griddynamics.jagger.jenkins.plugin.util.CommandTokenizer;
import hudson.Extension;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.*;
import org.apache.tools.ant.taskdefs.Echo;
import org.kohsuke.stapler.DataBoundConstructor;
import sun.font.Script;

import javax.xml.bind.PropertyException;
import java.io.*;
import java.nio.channels.FileChannel;
import java.util.*;


public class JaggerEasyDeployPlugin extends Builder
{

    //to collect nodes in one field.
    private ArrayList<Node> nodList = new ArrayList<Node>();

    //the collect nodes to attack in one field.
    private ArrayList<NodeToAttack> nodesToAttack = new ArrayList<NodeToAttack>();

    private final String PROPERTIES_PATH = "/properties";    // where we will store properties for Jagger for each node

    private MyProperties commonProperties ;

    private StringBuilder deploymentScript;


    //hard code; like in smoke-test
    //maybe it's better to ask path of Jagger?

    String VERSION = "1.1.3-SNAPSHOT";

    /**
     * Constructor where fields from *.jelly will be passed
     * @param nodesToAttack
     *                      List of nodes to attack
     * @param nodList
     *               List of nodes to do work
     */
    @DataBoundConstructor
    public JaggerEasyDeployPlugin(ArrayList<NodeToAttack> nodesToAttack, ArrayList<Node> nodList){

        this.nodesToAttack = nodesToAttack;
        this.nodList = nodList;

        setUpCommonProperties();
    }

    public ArrayList<NodeToAttack> getNodesToAttack() {
        return nodesToAttack;
    }

    public ArrayList<Node> getNodList() {
        return nodList;
    }

    public StringBuilder getDeploymentScript() {
        return deploymentScript;
    }

    public String getVERSION() {
        return VERSION;
    }

    public void setVERSION(String VERSION) {
        this.VERSION = VERSION;
    }

    /**
     * Loading EnvVars and create properties_files
     * @param build .
     * @param listener .
     * @return true
     */
    @Override
    public boolean prebuild(Build build, BuildListener listener) {

        PrintStream logger = listener.getLogger();

        try {

            checkAddressesOnBuildVars(build.getEnvVars());//build.getEnvVars() this works, but deprecated

            //create folder to collect properties files
            File folder = new File(build.getWorkspace()+PROPERTIES_PATH);
            if(!folder.exists()) {folder.mkdirs();}
            logger.println("\nFOLDER WORKSPACE\n"+folder.toString()+"\n\n");

            for(Node node: nodList){

                generatePropertiesFile(node,folder);
            }

            generateScriptToDeploy();

            logger.println(deploymentScript.toString());

        } catch (Exception e) {
            logger.println("Exception in preBuild: " + e);
        }

     //   listener.getLogger().println(System.getProperties().stringPropertyNames());

        return true;
    }


    /**
     * generating script like in smoke-test, to execute it in perform method
     */
    private void generateScriptToDeploy() {

        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n\n");
        script.append("basedir=`pwd` \n" );
        script.append("TimeStart=`date +%y/%m/%d_%H:%M`\n\n");

        downloadTestsToRepo(script);

        killOldJagger(script);

        startAgents(script);

        script.append("\nsleep(5)\n");

        startNodes(script);



        deploymentScript = script;

    }

    /**
     * Starting Nodes with specific property file for each
     * @param script deploymentScript
     */
    private void startNodes(StringBuilder script) {

        //here we should run start.sh with properties file that on our computer, not on Nodes.
        //it means that first - we should transfer it to Node
        script.append("\nCopying properties to remote Nodes and start\n");

        Node coordinator = null; //COORDINATION_SERVER should start in very last order

        for(Node node : nodList) {
            String userName = node.getUserName();
            String address = node.getServerAddressActual();
            String keyPath = node.getFinalPropertiesPath();
            String jaggerHome = "/home/" + userName + "/runned_jagger";
            String newPropPath = jaggerHome + "/tempProperiesToDeploy";

            File property = new File(newPropPath);
            if(!property.exists()){
                //noinspection ResultOfMethodCallIgnored
                property.mkdirs();
            }
            scpSendKey(userName, address, keyPath, node.getFinalPropertiesPath(), newPropPath,script);
            node.setFinalPropertiesPath(newPropPath + "/" + node.getServerAddressActual() + ".properties");

            if(node.getCoordinationServer() != null) {
                coordinator = node;
            } else {
                script.append("\necho ").append(address).append(" : cd ").append(jaggerHome).append("; ./start.sh ").append(node.getFinalPropertiesPath()).append("\n");
                doOnVmSSH(userName, address, keyPath, "cd " + jaggerHome + "; ./start.sh " + node.getFinalPropertiesPath(), script);
            }
        }

        if (coordinator != null) {
            String userName = coordinator.getUserName();
            String address = coordinator.getServerAddressActual();
            String keyPath = coordinator.getFinalPropertiesPath();
            String jaggerHome = "/home/" + userName + "/runned_jagger";

            script.append("\necho ").append(userName).append(" : cd ").append(jaggerHome).append("; ./start.sh ").append(keyPath).append("\n");
            doOnVmSSH(userName, address, keyPath, "cd " + jaggerHome + "; ./start.sh " + keyPath, script);
        }

    }

    /**
     * Starting Agents, if it declared
     * @param script deploymentScript
     */
    private void startAgents(StringBuilder script) {

        for(NodeToAttack node : nodesToAttack){
            if(node.isInstallAgent()) {
                String jaggerHome = "/home/" + node.getUserName() + "/runned_jagger";

                killOldJagger1(node.getUserName(), node.getServerAddressActual(), node.getSshKeyPath(), jaggerHome, script);

                script.append("\necho Starting Agents\n");
                script.append("echo \"").append(node.getServerAddressActual()).append(" : cd ").append(jaggerHome).append("; ./start_agent.sh\"\n");

                String command = "cd " + jaggerHome + "; ./start_agent.sh -Dchassis.coordination.http.url=" + commonProperties.get("chassis.coordination.http.url");
                doOnVmSSHDaemon(node.getUserName(), node.getServerAddress(), node.getSshKeyPath(), command, script);
                script.append("> /dev/null");

            }
        }
    }


    /**
     * kill old Jagger , deploy new one , stop processes in jagger
     * @param script String Builder of deployment Script
     */
    private void killOldJagger(StringBuilder script) {

        script.append("\necho Killing old jagger\n\n");
        for(Node node:nodList){

            String jaggerHome = "/home/" + node.getUserName() + "/runned_jagger";

            killOldJagger1(node.getUserName(),node.getServerAddressActual(), node.getSshKeyPath(), jaggerHome,  script);
        }

    }

    private void killOldJagger1(String userName, String serverAddress, String keyPath, String jaggerHome, StringBuilder script){

        script.append("echo TRYING TO DEPLOY NODE ").append(userName).append("@").append(serverAddress).append("\n\n");
        doOnVmSSH(userName, serverAddress, keyPath, "rm -rf " + jaggerHome, script);
        doOnVmSSH(userName, serverAddress, keyPath, "mkdir " + jaggerHome, script);

        script.append("\n");

        scpSendKey(userName,
                serverAddress,
                keyPath,
                "~/.m2/repository/com/griddynamics/jagger/jagger-test/$VERSION/jagger-test-" + VERSION + "-full.zip",
                jaggerHome, script);
        doOnVmSSH(userName, serverAddress, keyPath,
                "unzip " + jaggerHome + "/jagger-test-" + VERSION + "-full.zip -d " + jaggerHome, script);

        script.append("\necho KILLING PREVIOUS PROCESS ").append(userName).append("@").append(serverAddress).append("\n");
        doOnVmSSH(userName, serverAddress, keyPath, jaggerHome + "/stop.sh", script);
        doOnVmSSH(userName, serverAddress, keyPath, jaggerHome + "/stop_agent.sh", script);
        doOnVmSSH(userName, serverAddress, keyPath, jaggerHome + "rm -rf /home/" + userName + "/jaggerdb", script);
        script.append("\n");
    }



    /**
     * @param script main deployment script
     */
    private void downloadTestsToRepo(StringBuilder script) {

        script.append("# download tests to repo\n");

        script.append("mvn org.apache.maven.plugins:maven-dependency-plugin:2.4:get -Dartifact=com.griddynamics.jagger:jagger-test:");
        script.append(VERSION).append(":zip:full -DrepoUrl=https://nexus.griddynamics.net/nexus/content/repositories#/jagger-snapshots/\n");

        script.append("mvn org.apache.maven.plugins:maven-dependency-plugin:2.4:get -Dartifact=com.griddynamics.jagger:test-target:");
        script.append(VERSION).append(":war -DrepoUrl=https://nexus.griddynamics.net/nexus/content/repositories/jagger-snapshots/\n\n");
    }

    /**
     * Check if Build Variables contain addresses , or VERSION (of Jagger)
     * @param ev Build Variables
     */
    private void checkAddressesOnBuildVars(Map<String,String> ev) {

        String address;

        for(Node node: nodList){

                address = node.getServerAddress();
                if(address.matches("\\$.+")) {

                    address = address.substring(1,address.length());

                    if(address.matches("\\{.+\\}")){
                        address = address.substring(1,address.length()-1);
                    }

                    if(ev.containsKey(address)){
                        node.setServerAddressActual(ev.get(address));
                    }
                }
        }

        for(NodeToAttack node : nodesToAttack){

            address = node.getServerAddress();
            if(address.matches("\\$.+")) {

                address = address.substring(1,address.length());

                if(address.matches("\\{.+\\}")){
                    address = address.substring(1,address.length()-1);
                }

                if(ev.containsKey(address)){
                    node.setServerAddressActual(ev.get(address));
                }
            }
        }

        checkVersionOnBuildVars(ev);

    }

    /**
     * rewriting fields on special class foe properties
     */
    private void setUpCommonProperties() {

        commonProperties = new MyProperties();

        for(Node node : nodList) {
            if(node.getRdbServer() != null) {
                setUpRdbProperties(node);
            }
            if(node.getCoordinationServer() !=  null) {
                setUpCoordinationServerPropeties(node);
            }
        }

        for(NodeToAttack node : nodesToAttack) {
            setUpNodeToAttack(node);
        }

    }

    /**
     * Setting up Common Properties for Nodes
     * @param node node to attack
     */
    private void setUpNodeToAttack(NodeToAttack node) {

        String key = "test.service.endpoints";
        if(commonProperties.get(key) == null){
            commonProperties.setProperty(key, node.getServerAddressActual());
        } else {
            commonProperties.addValueWithComma(key, node.getServerAddressActual());
        }
    }

    /**
     * Setting up Common Properties for Nodes
     * @param node Node that play CoordinationServer Role
     */
    private void setUpCoordinationServerPropeties(Node node) {

        commonProperties.setProperty("chassis.coordinator.zookeeper.endpoint",node.getServerAddressActual() +
                                            ":" + node.getCoordinationServer().getPort());
        //Is this property belong to Coordination Server
        commonProperties.setProperty("chassis.storage.fs.default.name","hdfs://"+node.getServerAddressActual() + "/");
        commonProperties.setProperty("chassis.coordination.http.url","http://" + node.getServerAddressActual() + ":8089");
        //port of http.url hard code? or it can be set somewhere
    }

    /**
     * Setting up Common Properties for Nodes
     * @param node Node that play RdbServer Role
     */
    private void setUpRdbProperties(Node node) {
        //if using H2 !!!
        commonProperties.setProperty("chassis.storage.rdb.client.driver", "org.h2.Driver");
        commonProperties.setProperty("chassis.storage.rdb.client.url","jdbc:h2:tcp://" +
                        node.getServerAddressActual() + ":" + node.getRdbServer().getRdbPort() +"/jaggerdb/db");
        commonProperties.setProperty("chassis.storage.rdb.username","jagger");
        commonProperties.setProperty("chassis.storage.rdb.password","rocks");
        commonProperties.setProperty("chassis.storage.hibernate.dialect","org.hibernate.dialect.H2Dialect");
        //standard port 8043 ! can it be changed? or hard code for ever?
        //if external bd ...
    }

    /**
     * Generating properties file for Node
     * @param node specified node
     * @param folder where to write file
     * @throws java.io.IOException /
     * @throws javax.xml.bind.PropertyException /
     */
    private void generatePropertiesFile(Node node, File folder) throws PropertyException, IOException {

        File filePath = new File(folder+"/"+node.getServerAddressActual()+".properties");

        if(!node.getPropertiesPath().matches("\\s*")) {
            copyFile(new File(node.getPropertiesPath()),filePath);       // copy to jenkins
        }

        MyProperties properties = new MyProperties();

        if(filePath.exists()){
            properties.load(new FileInputStream(filePath));
        }

        if(node.getMaster() != null){
            addMasterProperties(node, properties);
        }
        if(node.getCoordinationServer() != null){
            addCoordinationServerProperties(node, properties);
        }
        if(node.getRdbServer() != null){
            addRdbServerProperties(node, properties);
        }
        if(node.getReporter() != null){
            addReporterServerProperties(node,properties);
        }
        if(node.getKernel() != null){
            addKernelProperties(node, properties);
        }

        properties.store(new FileOutputStream(filePath), "generated automatically");
        node.setFinalPropertiesPath(filePath.toString());        //finalPropertiesPath - Path that Jenkins will use to run start.sh

    }


    //Copy File
    public static void copyFile(File sourceFile, File destFile) throws IOException {
    if(!destFile.exists()) {
        destFile.createNewFile();
    }

    FileChannel source = null;
    FileChannel destination = null;

    try {
        source = new FileInputStream(sourceFile).getChannel();
        destination = new FileOutputStream(destFile).getChannel();
        destination.transferFrom(source, 0, source.size());
    }
    finally {
        if(source != null) {
            source.close();
        }
        if(destination != null) {
            destination.close();
        }
    }
}

    /**
     * Adding Reporter Server Properties
     * @param node  Node instance
     * @param properties    property of specified Node
     */
    private void addKernelProperties(Node node, MyProperties properties) {

        String key = "chassis.roles";
        if(properties.get(key) == null){
            properties.setProperty(key, node.getKernel().getRoleType().toString());
        } else {
            properties.addValueWithComma(key,node.getReporter().getRoleType().toString());
        }

        key = "chassis.coordinator.zookeeper.endpoint";
        properties.setProperty(key, commonProperties.getProperty(key));

        key = "chassis.storage.fs.default.name";
        properties.setProperty(key, commonProperties.getProperty(key));

        addDBProperties(properties);

        key = "test.service.endpoints";
        properties.setProperty(key, commonProperties.getProperty(key));
    }

    /**
     * Adding Reporter Server Properties
     * @param node  Node instance
     * @param properties    property of specified Node
     */
    private void addReporterServerProperties(Node node, MyProperties properties) {

        String key = "chassis.roles";
        if(properties.get(key) == null){
            properties.setProperty(key, node.getRdbServer().getRoleType().toString());
        } else {
            properties.addValueWithComma(key,node.getReporter().getRoleType().toString());
        }


    }

    /**
     * Adding RDB Server Properties
     * @param node  Node instance
     * @param properties    property of specified Node
     */
    private void addRdbServerProperties(Node node, MyProperties properties) {

        String key = "chassis.roles";
        if(properties.get(key) == null){
            properties.setProperty(key, node.getRdbServer().getRoleType().toString());
        } else {
            properties.addValueWithComma(key,node.getRdbServer().getRoleType().toString());
        }

    }

    /**
     * Adding Coordination Server Properties
     * @param node  Node instance
     * @param properties    property of specified Node
     */
    private void addCoordinationServerProperties(Node node, MyProperties properties) {

        String key = "chassis.roles";
        if(properties.get(key) == null){
            properties.setProperty(key, node.getCoordinationServer().getRoleType().toString());
        } else {
            properties.addValueWithComma(key,node.getCoordinationServer().getRoleType().toString());
        }
    }



    /**
     * Adding Master Properties
     * @param node  Node instance
     * @param properties    property of specified Node
     * @throws javax.xml.bind.PropertyException /
     */
    private void addMasterProperties(Node node, MyProperties properties) throws PropertyException {

        String key = "chassis.roles";
        if(properties.getProperty(key) == null){
            properties.setProperty(key, node.getMaster().getRoleType().toString());
        } else {
            properties.addValueWithComma(key,node.getMaster().getRoleType().toString());
        }
        //Http coordinator will always be on Master node (on port 8089?)!
        properties.addValueWithComma(key, "HTTP_COORDINATION_SERVER");

        key = "chassis.coordinator.zookeeper.endpoint";
        properties.setProperty(key, commonProperties.getProperty(key));

        key = "chassis.storage.fs.default.name";
        properties.setProperty(key, commonProperties.getProperty(key));

        addDBProperties(properties);

        key = "test.service.endpoints";
        properties.setProperty(key, commonProperties.getProperty(key));
    }



    /**
     * Adding Data Base Properties
     * @param properties    property of specified Node
     */
    private void addDBProperties(MyProperties properties) {

        String key;

        key = "chassis.storage.rdb.client.driver";
        properties.setProperty(key, commonProperties.getProperty(key));

        key = "chassis.storage.rdb.client.url";
        properties.setProperty(key, commonProperties.getProperty(key));

        key = "chassis.storage.rdb.username";
        properties.setProperty(key, commonProperties.getProperty(key));

        key = "chassis.storage.rdb.password";
        properties.setProperty(key, commonProperties.getProperty(key));

        key = "chassis.storage.hibernate.dialect";
        properties.setProperty(key, commonProperties.getProperty(key));
    }


    // Start's processes on machine     ProcStarter is not serializeble
    transient private Launcher.ProcStarter procStarter = null;


    /**
     * This method will be called in build time (when you build job)
     * @param build   .
     * @param launcher .
     * @param listener .
     * @return boolean : true if build passed, false in other way
     * @throws InterruptedException
     * @throws IOException
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        PrintStream logger = listener.getLogger();
        logger.println("\n______Jagger_Easy_Deploy_Started______\n");
        logger.println("\n______DEBUG_INFORMATION_NODES_WITH_ROLES______\n");

        checkVersionOnBuildVars(build.getEnvVars());



        try{

        //    logInfoAboutNodes(logger);

            setUpProcStarter(launcher,build);



            return true;

        }catch (Exception e){
            logger.println("Troubles : " +e);
        }
            return false;
    }


    /**
     * Copy files via scp using public key autorisation
     * @param userName user name
     * @param address   address of machine
     * @param keyPath   path of private key
     * @param filePathFrom  file path that we want to copy
     * @param filePathTo  path where we want to store file
     * @return exit code of command
     * @throws java.io.IOException .
     * @throws InterruptedException .
     */
    private int scpGetKey(String userName, String address, String keyPath, String filePathFrom, String filePathTo)
            throws IOException, InterruptedException {

        StringBuilder strB = new StringBuilder();
        strB.append("scp -i ");
        strB.append(keyPath);
        strB.append(" ");
        strB.append(userName);
        strB.append("@");
        strB.append(address);
        strB.append(":");
        strB.append(filePathFrom);
        strB.append(" ");
        strB.append(filePathTo);

        return procStarter.cmds(stringToCmds(strB.toString())).start().join();
    }


    /**
     * Copy files via scp using public key autorisation
     * @param userName user name
     * @param address   address of machine
     * @param keyPath   path of private key
     * @param filePathFrom  file path that we want to copy
     * @param filePathTo  path where we want to store file
     * @param script String Builder for deployment script
     */
    private void scpSendKey(String userName, String address, String keyPath, String filePathFrom, String filePathTo, StringBuilder script) {

        script.append("scp -i ");
        script.append(keyPath);
        script.append(" ");
        script.append(filePathFrom);
        script.append(" ");
        script.append(userName);
        script.append("@");
        script.append(address);
        script.append(":");
        script.append(filePathTo).append("\n");

    }


    /**
     *  if Version of Jagger given in variables of environment (if can't find - uses default version)
     *  it should be given as VERSION
     * @param envVars variables of environment
     */
    private void checkVersionOnBuildVars(Map<String, String> envVars) {

        if(envVars.containsKey("VERSION")){
            setVERSION(envVars.get("VERSION"));
        }
    }

    private void setUpProcStarter(Launcher launcher, AbstractBuild<?, ?> build) {

        procStarter = launcher.new ProcStarter();
        procStarter.envs();
        procStarter.pwd(System.getProperty("user.home"));
    }



    /**
     * do commands on remote machine via ssh using public key authorisation
     *
     * @param userName user name
     * @param address address of machine
     * @param keyPath path to private key
     * @param commandString command
     * @param script String Builder where we merge all commands
     */
    private void doOnVmSSH(String userName, String address, String keyPath, String commandString,StringBuilder script) {

   //     script.append("echo remout run \"").append(commandString).append("/home/").append(userName).append("/runned_jagger" + "\"\n");
        script.append("ssh -i ").append(keyPath).append(" ").append(userName).append("@").append(address).append(" ").append(" ").append(commandString).append("\n");

    }



    /**
     * not yet implemented
     * do commands on remote machine via ssh using password key authorisation
     *
     * @param userName /                 look doOnVmSSh(...)
     * @param address   /
     * @param password   password of user
     * @param commandString /
     * @return               /
     * @throws java.io.IOException /
     * @throws InterruptedException /
     */
    private void doOnVmSSHPass(String userName, String address, String password, String commandString) throws IOException, InterruptedException {
       //not yet implemented
        procStarter.cmds(stringToCmds("ssh " + userName + "@" + address + " " + commandString)).start().join();
    }



    /**
     * do commands daemon on remote machine via ssh using public key authorisation
     *
     * @param userName user name
     * @param address address of machine
     * @param keyPath path to private key
     * @param commandString command
     * @param script String Builder where we merge all commands
     */
    private void doOnVmSSHDaemon(String userName, String address, String keyPath, String commandString,StringBuilder script) {

        script.append("echo remout run \"").append(commandString).append("/home/").append(userName).append("/runned_jagger" + "\"\n");
        script.append("ssh -f -i ").append(keyPath).append(" ").append(userName).append("@").append(address).append(" ").append(commandString).append("\n");
    }



    /**
     * String to array
     * cd directory >> [cd, directory]
     * @param str commands in ine string
     * @return array of commands
     */
    private String[] stringToCmds(String str){
        return QuotedStringTokenizer.tokenize(str);
    }


    /**
     *  log information about all Nodes
     * @param logger listener.getLogger from perform method
     */
    private void logInfoAboutNodes(PrintStream logger) {

        for(NodeToAttack node:nodesToAttack){
                logger.println("-------------------------");
                logger.println(node.toString());
                }
                logger.println("-------------------------\n\n");

        for(Node node:nodList){
            logger.println("-------------------------");
            logger.println("Node address : "+node.getServerAddressActual());
            logger.println("-------------------------");
            logger.println("Node properties path : "+node.getPropertiesPath());
            logger.println("-------------------------");
            logger.println("Node's roles : ");
            if(!node.getHmRoles().isEmpty()){
                for(Role role: node.getHmRoles().values()){
                    logger.println(role.toString());
                }
            } else {
                logger.println(node.getPropertiesPath());
            }
            logger.println("-------------------------\n-------------------------");
        }
    }

    /**
     * Unnecessary, but recommended for more type safety
     * @return Descriptor of this class
     */
    @Override
    public Descriptor<Builder> getDescriptor() {
        return (DescriptorJEDP)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorJEDP  extends BuildStepDescriptor<Builder>
    {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            //how it names in build step config
            return "Jagger Easy Deploy";
        }


////    validation for nodeList not yet implemented
//        /**
//         * To test number of each role
//         * @param nodList whole list of nodes that do work
//         * @return OK if it's OK, ERROR otherwise
//         */
//        public FormValidation doCheckNodList(@QueryParameter final ArrayList<Node> nodList){
//
//
//
//            int numberOfMasters = 0,
//                numberOfCoordServers = 0,
//                numberOfKernels = 0,
//                numberOfRdbServers = 0,
//                numberOfReporters = 0;
//
//            try{
//
//                for(Node node:(List<Node>)nodList){
//                    for(Role role:node.getHmRoles().values()){
//                        if (role instanceof Kernel){
//                            numberOfKernels ++;
//                        } else if (role instanceof Master){
//                            numberOfMasters ++;
//                        } else if (role instanceof CoordinationServer){
//                            numberOfCoordServers ++;
//                        } else if (role instanceof Reporter){
//                            numberOfReporters ++;
//                        } else if (role instanceof RdbServer){
//                            numberOfRdbServers ++;
//                        } else {
//                            throw new Exception("Where this come from? Not role!"); //temporary decision
//                        }
//                    }
//                }
//
//                if(numberOfCoordServers == 0){
//                    return FormValidation.error("no COORDINATION_SERVER was found");
//                } else if (numberOfCoordServers > 1){
//                    return FormValidation.error("more then one COORDINATION_SERVER was found");
//                }
//
//                if(numberOfMasters == 0){
//                    return FormValidation.error("no MASTER was found");
//                } else if (numberOfMasters > 1) {
//                    return FormValidation.error("more then one MASTER was found");
//                }
//
//                if(numberOfRdbServers == 0){
//                    return FormValidation.error("no RDB_SERVER was found");
//                } else if (numberOfRdbServers > 1){
//                    return FormValidation.error("more then one RDB_SERVER was found");
//                }
//
//                if(numberOfReporters == 0){
//                    return FormValidation.error("no REPORTER was found");
//                } else if (numberOfReporters > 1){
//                    return FormValidation.error("more then one REPORTER was found");
//                }
//
//                if(numberOfKernels == 0){
//                    return FormValidation.error("no KERNEL was found");
//                }
//
//                return FormValidation.ok(nodList.getClass().getName());
//            } catch (Exception e) {
//                return FormValidation.error("something wrong");
//            }
//        }
    }

}
