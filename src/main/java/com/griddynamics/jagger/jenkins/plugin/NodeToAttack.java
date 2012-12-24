package com.griddynamics.jagger.jenkins.plugin;


import ch.ethz.ssh2.*;
//import com.jcraft.jsch.JSch;
//import com.jcraft.jsch.JSchException;
//import com.jcraft.jsch.Session;
//import com.jcraft.jsch.UserInfo;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.Properties;

/**
 * Created with IntelliJ IDEA.
 * User: amikryukov
 * Date: 12/21/12
 */


/**
 * To Make Object of Server that we want to test
 */
public class NodeToAttack extends Role implements Describable<NodeToAttack> {

    private final String serverAddress;

    private final boolean installAgent;

    private final String userName;

    private final String userPassword;

    private final String sshKeyPath ;

    private final boolean usePassword;


    @DataBoundConstructor
    public NodeToAttack(String serverAddress , boolean installAgent, String userName, String sshKeyPath,
                        boolean usePassword, String userPassword){

        this.serverAddress = serverAddress;
        this.installAgent = installAgent;
        this.userName = userName;
        this.sshKeyPath = sshKeyPath;
        this.usePassword = usePassword;
        this.userPassword = userPassword;
    }

    public String getUserName() {
        return userName;
    }

    public String getUserPassword() {
        return userPassword;
    }

    public String getSshKeyPath() {
        return sshKeyPath;
    }

    public boolean isUsePassword() {
        return usePassword;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public boolean isInstallAgent() {
        return installAgent;
    }

    public Descriptor<NodeToAttack> getDescriptor() {

        return Hudson.getInstance().getDescriptor(getClass());
    }

    @Override
    public String toString() {
        return "NodeToAttack{" +
                "serverAddress='" + serverAddress + '\'' +
                ", installAgent=" + installAgent +
                ", userName='" + userName + '\'' +
                ", userPassword='" + userPassword + '\'' +
                ", sshKeyPath='" + sshKeyPath + '\'' +
                ", usePassword=" + usePassword +
                '}';
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<NodeToAttack>{

        @Override
        public String getDisplayName() {
            return "NodeToAttack";
        }

        /**
         * For test Server Address if it available
         * @param value String from Server Address form
         * @return OK if ping, ERROR otherwise
         */
        public FormValidation doCheckServerAddress(@QueryParameter String value) {

            try {

                if(value == null || value.matches("\\s*")) {
                    return FormValidation.error("Set Address");
                }

                InetAddress.getByName(value).isReachable(1000);
                return FormValidation.ok();

            } catch(UnknownHostException e){

                return FormValidation.error("Bad Server Address");
            } catch (IOException e) {

                return FormValidation.error("Server With That Address Unavailable");
            }
        }

        /**
         * For test ssh connection
         * @param serverAddress   STRING host address
         * @param userName        STRING user name
         * @param sshKeyPath      STRING path of ssh Key
         * @param usePassword     BOOLEAN if password in use
         * @param userPassword    STRING user password
         * @return   ok if connect, error otherwise
         */
        public FormValidation doTestSSHConnection(@QueryParameter("serverAddress") final String serverAddress,
                                               @QueryParameter("userName") final String userName,
                                               @QueryParameter("sshKeyPath") final String sshKeyPath,
                                               @QueryParameter("usePassword") final boolean usePassword,
                                               @QueryParameter("userPassword") final String userPassword) {
            try {

                Connection conn = new Connection(serverAddress);
                conn.connect();

                if(!usePassword){

                    File rsaKeyFile = new File(sshKeyPath);

                    if(!conn.authenticateWithPublicKey(userName,rsaKeyFile,"")) {
                        return FormValidation.error("can't authenticate with pub.key");
                    }
                } else {
                    if(!conn.authenticateWithPassword(userName,userPassword)) {

                        return FormValidation.error("can't authenticate with password");
                    }
                }

                return FormValidation.ok();

            } catch (ConnectException e) {
                return FormValidation.error("can't make even connection");
            } catch (IOException e) {
                return FormValidation.error("Can't connect with such configuration\n"+e.getLocalizedMessage());
            }
        }

    }

}