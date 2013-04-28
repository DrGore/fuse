/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.fabric.internal;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.karaf.jaas.modules.Encryption;
import org.apache.karaf.jaas.modules.encryption.EncryptionSupport;
import org.apache.zookeeper.KeeperException;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.CreateEnsembleOptions;
import org.fusesource.fabric.api.DataStore;
import org.fusesource.fabric.api.FabricException;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.ZooKeeperClusterService;
import org.fusesource.fabric.utils.HostUtils;
import org.fusesource.fabric.utils.Ports;
import org.fusesource.fabric.utils.SystemProperties;
import org.fusesource.fabric.zookeeper.ACLManager;
import org.fusesource.fabric.zookeeper.IZKClient;
import org.fusesource.fabric.zookeeper.ZkDefs;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.fusesource.fabric.utils.BundleUtils.findAndStopBundle;
import static org.fusesource.fabric.utils.BundleUtils.findOrInstallBundle;
import static org.fusesource.fabric.utils.BundleUtils.installOrStopBundle;
import static org.fusesource.fabric.utils.Ports.mapPortToRange;
import static org.fusesource.fabric.zookeeper.ZkPath.AUTHENTICATION_CRYPT_ALGORITHM;
import static org.fusesource.fabric.zookeeper.ZkPath.AUTHENTICATION_CRYPT_PASSWORD;
import static org.fusesource.fabric.zookeeper.ZkPath.CONFIG_CONTAINER;
import static org.fusesource.fabric.zookeeper.ZkPath.CONFIG_ENSEMBLE;
import static org.fusesource.fabric.zookeeper.ZkPath.CONFIG_ENSEMBLES;
import static org.fusesource.fabric.zookeeper.ZkPath.CONFIG_ENSEMBLE_PASSWORD;
import static org.fusesource.fabric.zookeeper.ZkPath.CONFIG_ENSEMBLE_PROFILE;
import static org.fusesource.fabric.zookeeper.ZkPath.CONFIG_ENSEMBLE_URL;
import static org.fusesource.fabric.zookeeper.ZkPath.CONFIG_VERSIONS_CONTAINER;
import static org.fusesource.fabric.zookeeper.ZkPath.CONTAINER_ALIVE;
import static org.fusesource.fabric.zookeeper.ZkPath.CONTAINER_IP;
import static org.fusesource.fabric.zookeeper.ZkPath.CONTAINER_PORT_MAX;
import static org.fusesource.fabric.zookeeper.ZkPath.CONTAINER_PORT_MIN;
import static org.fusesource.fabric.zookeeper.internal.curator.Constants.ZOOKEEPER_PASSWORD;
import static org.fusesource.fabric.zookeeper.internal.curator.Constants.ZOOKEEPER_URL;
import static org.fusesource.fabric.zookeeper.internal.curator.Constants.ENSEMBLE_ID;
import static org.fusesource.fabric.zookeeper.internal.curator.Constants.CONNECTION_TIMEOUT;
import static org.fusesource.fabric.zookeeper.internal.curator.Constants.RETRY_POLICY_BASE_SLEEP_TIME_MS;
import static org.fusesource.fabric.zookeeper.internal.curator.Constants.RETRY_POLICY_MAX_RETRIES;
import static org.fusesource.fabric.zookeeper.internal.curator.Constants.RETRY_POLICY_MAX_SLEEP_TIME_MS;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.add;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.copy;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.createDefault;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.deleteSafe;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.exists;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.generatePassword;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.get;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.getSubstitutedData;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.getSubstitutedPath;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.remove;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.set;

public class ZooKeeperClusterServiceImpl implements ZooKeeperClusterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZooKeeperClusterServiceImpl.class);

    private BundleContext bundleContext;
	private ConfigurationAdmin configurationAdmin;
    private CuratorFramework curator;
    private ACLManager ACLManager;
	private FabricService fabricService;
    private DataStore dataStore;
    private boolean ensembleAutoStart = Boolean.parseBoolean(System.getProperty(SystemProperties.ENSEMBLE_AUTOSTART));

    public void init() {
        if (ensembleAutoStart) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    createLocalServer();
                }
            }).start();

        }
    }

    public BundleContext getBundleContext() {
        return bundleContext;
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public ConfigurationAdmin getConfigurationAdmin() {
        return configurationAdmin;
    }

    public void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

	public FabricService getFabricService() {
		return fabricService;
	}

	public void setFabricService(FabricService fabricService) {
		this.fabricService = fabricService;
	}

    public CuratorFramework getCurator() {
        return curator;
    }

    public void setCurator(CuratorFramework curator) {
        this.curator = curator;
    }

    public ACLManager getACLManager() {
        return ACLManager;
    }

    public void setACLManager(ACLManager ACLManager) {
        this.ACLManager = ACLManager;
    }

    public void setDataStore(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    public DataStore getDataStore() {
        return dataStore;
    }

    public void createLocalServer() {
        createLocalServer(Ports.DEFAULT_ZOOKEEPER_SERVER_PORT);
    }

    public void createLocalServer(int port) {
        String newUser = null;
        String newUserPassword = null;
        org.apache.felix.utils.properties.Properties userProps = null;

        try {
            userProps = new org.apache.felix.utils.properties.Properties(new File(System.getProperty("karaf.home") + "/etc/users.properties"));
        } catch (IOException e) {
            LOGGER.warn("Failed to load users from etc/users.properties. No users will be imported.", e);
        }

        String zookeeperPassword = System.getProperty(SystemProperties.ZOOKEEPER_PASSWORD);

        CreateEnsembleOptions createOpts = CreateEnsembleOptions.build();

        if (userProps != null && !userProps.isEmpty()) {
            newUser = (String) userProps.keySet().iterator().next();
            newUserPassword = (String) userProps.get(newUser);
            createOpts.user(newUser, newUserPassword);
        }

        if (zookeeperPassword != null && !zookeeperPassword.isEmpty()) {
            createOpts.zookeeperPassword(zookeeperPassword);
        }

        createLocalServer(port, createOpts);
    }

    public void createLocalServer(int port, CreateEnsembleOptions options) {
        try {
            IZKClient client;
            Hashtable<String, Object> properties;
            String version = ZkDefs.DEFAULT_VERSION;
            String karafName = System.getProperty(SystemProperties.KARAF_NAME);
            String minimumPort = System.getProperty(ZkDefs.MINIMUM_PORT);
            String maximumPort = System.getProperty(ZkDefs.MAXIMUM_PORT);
            int mappedPort = mapPortToRange(port, minimumPort, maximumPort);

            if (options.getZookeeperPassword() != null) {
                //do nothing
            } else if (System.getProperties().containsKey(SystemProperties.ZOOKEEPER_PASSWORD)) {
                options.setZookeeperPassword(System.getProperty(SystemProperties.ZOOKEEPER_PASSWORD));
            } else {
                options.setZookeeperPassword(generatePassword());
            }

			// Install or stop the fabric-configadmin bridge
			Bundle bundleFabricAgent = findAndStopBundle(bundleContext, "org.fusesource.fabric.fabric-agent");
			Bundle bundleFabricConfigAdmin = installOrStopBundle(bundleContext, "org.fusesource.fabric.fabric-configadmin",
					"mvn:org.fusesource.fabric/fabric-configadmin/" + FabricConstants.FABRIC_VERSION);
			Bundle bundleFabricZooKeeper = installOrStopBundle(bundleContext, "org.fusesource.fabric.fabric-zookeeper",
					"mvn:org.fusesource.fabric/fabric-zookeeper/" + FabricConstants.FABRIC_VERSION);
			Bundle bundleFabricJaas = installOrStopBundle(bundleContext, "org.fusesource.fabric.fabric-jaas  ",
					"mvn:org.fusesource.fabric/fabric-jaas/" + FabricConstants.FABRIC_VERSION);
			Bundle bundleFabricCommands = installOrStopBundle(bundleContext, "org.fusesource.fabric.fabric-commands  ",
					"mvn:org.fusesource.fabric/fabric-commands/" + FabricConstants.FABRIC_VERSION);
			Bundle bundleFabricMavenProxy = installOrStopBundle(bundleContext, "org.fusesource.fabric.fabric-commands  ",
					"mvn:org.fusesource.fabric/fabric-maven-proxy/" + FabricConstants.FABRIC_VERSION);

			// Create configuration
			String connectionUrl = HostUtils.getLocalHostName() + ":" + Integer.toString(mappedPort);
			String autoImportFrom = System.getProperty(SystemProperties.PROFILES_AUTOIMPORT_PATH);

            Properties curatorFrameworkProperties = createCuratorFrameworkProperties(connectionUrl, options.getZookeeperPassword(), autoImportFrom);

			setUpZookeeperServer(configurationAdmin, mappedPort, autoImportFrom);
            setupCuratorFramework(configurationAdmin, curatorFrameworkProperties);

			// Start fabric-zookeeper bundle
			bundleFabricZooKeeper.start();

			// Wait for the client to be available
			//client = waitForZookeeper(bundleContext, 5000);
            CuratorFramework curator = waitForCurator(bundleContext, 5000);

			// Import data into zookeeper
			if (autoImportFrom != null) {
                getDataStore().importFromFileSystem(autoImportFrom);
			}

            getDataStore().setDefaultVersion(version);

            // configure default profile
            String defaultProfile = getDataStore().getProfile(version, "default", true);

            set(curator, CONFIG_ENSEMBLE_URL.getPath(), "${zk:" + karafName + "/ip}:" + Integer.toString(mappedPort));
            set(curator, CONFIG_ENSEMBLE_PASSWORD.getPath(), options.getZookeeperPassword());

           curatorFrameworkProperties.setProperty(ZOOKEEPER_URL, "${zk:" + CONFIG_ENSEMBLE_URL.getPath() + "}");
           curatorFrameworkProperties.setProperty(ZOOKEEPER_PASSWORD, "${zk:" + CONFIG_ENSEMBLE_PASSWORD.getPath() + "}");
           getDataStore().setFileConfiguration(version, defaultProfile, "org.fusesource.fabric.zookeeper.properties", DataStoreHelpers.toBytes(curatorFrameworkProperties));


            // configure the ensemble
            String ensembleProfile = getDataStore().getProfile(version, "fabric-ensemble-0000", true);
            getDataStore().setProfileAttribute(version, ensembleProfile, "abstract", "true");
            getDataStore().setProfileAttribute(version, ensembleProfile, "hidden", "true");

            Properties ensembleProps = new Properties();
            ensembleProps.put("tickTime", "2000");
            ensembleProps.put("initLimit", "10");
            ensembleProps.put("syncLimit", "5");
            ensembleProps.put("dataDir", "data/zookeeper/0000");

            populatePropertiesFrom(ensembleProps, autoImportFrom + "/fabric/configs/versions/1.0/profiles/default/org.fusesource.fabric.zookeeper.server.properties");
            getDataStore().setFileConfiguration(version, ensembleProfile, "org.fusesource.fabric.zookeeper.server-0000.properties", DataStoreHelpers.toBytes(ensembleProps));

            // configure this server in the ensemble
            String ensembleServerProfile = getDataStore().getProfile(version, "fabric-ensemble-0000-1", true);
            getDataStore().setProfileAttribute(version, ensembleServerProfile, "hidden", "true");
            getDataStore().setProfileAttribute(version, ensembleServerProfile, "parents", ensembleProfile);
            Properties serverProps = new Properties();
            serverProps.put("clientPort", String.valueOf(mappedPort));
            getDataStore().setFileConfiguration(version, ensembleServerProfile, "org.fusesource.fabric.zookeeper.server-0000.properties", DataStoreHelpers.toBytes(serverProps));

			set(curator, CONFIG_ENSEMBLES.getPath(), "0000");
			set(curator, CONFIG_ENSEMBLE.getPath("0000"), karafName);

            // configure fabric profile
            String fabricProfile = getDataStore().getProfile(version, "fabric", true);
            Properties agentProps = DataStoreHelpers.toProperties(getDataStore().getFileConfiguration(version, fabricProfile, "org.fusesource.fabric.agent.properties"));
            agentProps.put("feature.fabric-commands", "fabric-commands");
            getDataStore().setFileConfiguration(version, "fabric", "org.fusesource.fabric.agent.properties", DataStoreHelpers.toBytes(agentProps));

            createDefault(curator, CONFIG_CONTAINER.getPath(karafName), version);
			String assignedProfile = System.getProperty(SystemProperties.PROFILE);
			if (assignedProfile != null && !assignedProfile.isEmpty() && !"fabric".equals(assignedProfile)) {
				createDefault(curator, CONFIG_VERSIONS_CONTAINER.getPath(version, karafName), "fabric fabric-ensemble-0000-1 " + assignedProfile);
			} else {
				createDefault(curator, CONFIG_VERSIONS_CONTAINER.getPath(version, karafName), "fabric fabric-ensemble-0000-1");
			}


			// add auth
            Map<String, String> configs = new HashMap<String, String>();
            configs.put("encryption.enabled", "${zk:/fabric/authentication/encryption.enabled}" );
            getDataStore().setConfiguration(version, defaultProfile, "org.fusesource.fabric.jaas", configs);

            // outside of the profile storage area, so we'll keep these in zk
			createDefault(curator, "/fabric/authentication/encryption.enabled", "true");
            createDefault(curator, "/fabric/authentication/domain", "karaf");
			addUsersToZookeeper(curator, options.getUsers());

            createDefault(curator, AUTHENTICATION_CRYPT_ALGORITHM.getPath(), "PBEWithMD5AndDES");
            createDefault(curator, AUTHENTICATION_CRYPT_PASSWORD.getPath(), options.getZookeeperPassword());

            // Fix acls
            //aclManager.fixAcl("/", true);

			// Reset the autostart flag
			if (ensembleAutoStart) {
				System.setProperty(SystemProperties.ENSEMBLE_AUTOSTART, Boolean.FALSE.toString());
				File file = new File(System.getProperty("karaf.base") + "/etc/system.properties");
				org.apache.felix.utils.properties.Properties props = new org.apache.felix.utils.properties.Properties(file);
				props.put(SystemProperties.ENSEMBLE_AUTOSTART, Boolean.FALSE.toString());
				props.save();
			}

			// Restart fabric-configadmin bridge
			bundleFabricConfigAdmin.start();
			bundleFabricJaas.start();
			bundleFabricCommands.start();
			bundleFabricMavenProxy.start();

			//Check if the agent is configured to auto start.
			if (!System.getProperties().containsKey(SystemProperties.AGENT_AUTOSTART) || Boolean.parseBoolean(System.getProperty(SystemProperties.AGENT_AUTOSTART))) {
				bundleFabricAgent = findOrInstallBundle(bundleContext, "org.fusesource.fabric.fabric-agent  ",
						"mvn:org.fusesource.fabric/fabric-agent/" + FabricConstants.FABRIC_VERSION);
				bundleFabricAgent.start();
			}
		} catch (Exception e) {
			throw new FabricException("Unable to create zookeeper server configuration", e);
		}
	}

    /**
     * Populates the {@link Hashtable} with entries read from the specified {@link String}.
     * @param hashtable
     * @param from
     */
    private static void populatePropertiesFrom(Hashtable hashtable, String from) {
        InputStream is = null;
        Properties properties = new Properties();
        try {
            is = new FileInputStream(from);
            properties.load(is);
            for (String key : properties.stringPropertyNames()) {
                hashtable.put(key, properties.get(key));
            }
        } catch (Exception e) {
            // Ignore
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }


    public void clean() {
        try {
            for (; ; ) {
                Configuration[] configs = configurationAdmin.listConfigurations("(|(service.factoryPid=org.fusesource.fabric.zookeeper.server)(service.pid=org.fusesource.fabric.zookeeper))");
                if (configs != null && configs.length > 0) {
                    for (Configuration config : configs) {
                        config.delete();
                    }
                    Thread.sleep(100);
                } else {
                    break;
                }
            }
            File zkDir = new File("data/zookeeper");
            if (zkDir.isDirectory()) {
                File newZkDir = new File("data/zookeeper." + System.currentTimeMillis());
                if (!zkDir.renameTo(newZkDir)) {
                    newZkDir = zkDir;
                }
                delete(newZkDir);
            }
        } catch (Exception e) {
            throw new FabricException("Unable to delete zookeeper configuration", e);
        }
    }

    private static void delete(File dir) {
        if (dir.isDirectory()) {
            for (File child : dir.listFiles()) {
                delete(child);
            }
        }
        if (dir.exists()) {
            dir.delete();
        }
    }

	public List<String> getEnsembleContainers() {
		try {
			Configuration[] configs = configurationAdmin.listConfigurations("(service.pid=org.fusesource.fabric.zookeeper)");
			if (configs == null || configs.length == 0) {
				return Collections.emptyList();
			}
			List<String> list = new ArrayList<String>();
			if (exists(curator, CONFIG_ENSEMBLES.getPath()) != null) {
				String clusterId = get(curator, CONFIG_ENSEMBLES.getPath());
				String containers =get(curator, CONFIG_ENSEMBLE.getPath(clusterId));
				Collections.addAll(list, containers.split(","));
			}
			return list;
		} catch (Exception e) {
			throw new FabricException("Unable to load zookeeper quorum containers", e);
		}
	}

    public void createCluster(List<String> containers) {
        createCluster(containers, CreateEnsembleOptions.build());
    }

    public void createCluster(List<String> containers, CreateEnsembleOptions options) {
        try {
            if (options.getZookeeperPassword() != null) {
                //do nothing
            } else if (System.getProperties().containsKey(SystemProperties.ZOOKEEPER_PASSWORD)) {
                options.setZookeeperPassword(System.getProperty(SystemProperties.ZOOKEEPER_PASSWORD));
            } else {
                options.setZookeeperPassword(generatePassword());
            }

            if (containers == null || containers.size() == 2) {
                throw new IllegalArgumentException("One or at least 3 containers must be used to create a zookeeper ensemble");
            }
            Configuration config = configurationAdmin.getConfiguration("org.fusesource.fabric.zookeeper", null);
            String zooKeeperUrl = config != null && config.getProperties() != null ? (String) config.getProperties().get("zookeeper.url") : null;
            if (zooKeeperUrl == null) {
                if (containers.size() != 1 || !containers.get(0).equals(System.getProperty(SystemProperties.KARAF_NAME))) {
                    throw new FabricException("The first zookeeper cluster must be configured on this container only.");
                }
                createLocalServer(2181, options);
                return;
            }

            String version = getDataStore().getDefaultVersion();

            for (String container : containers) {
                Container c = fabricService.getContainer(container);
                if (exists(curator, CONTAINER_ALIVE.getPath(container)) == null) {
                    throw new FabricException("The container " + container + " is not alive");
                }
                String containerVersion = get(curator, CONFIG_CONTAINER.getPath(container));
                if (!version.equals(containerVersion)) {
                    throw new FabricException("The container " + container + " is not using the default-version:" + version);
                }
            }

			// Find used zookeeper ports
			Map<String, List<Integer>> usedPorts = new HashMap<String, List<Integer>>();
			String oldClusterId = get(curator, CONFIG_ENSEMBLES.getPath());
			if (oldClusterId != null) {
                String profile = "fabric-ensemble-" + oldClusterId;
                String pid = "org.fusesource.fabric.zookeeper.server-" + oldClusterId;

                Map<String, String> p = getDataStore().getConfiguration(version, profile, pid);

                if (p == null) {
                    throw new FabricException("Failed to find old cluster configuration for ID " + oldClusterId);
                }

				for (Object n : p.keySet()) {
					String node = (String) n;
					if (node.startsWith("server.")) {
						String data = getSubstitutedPath(curator, CONFIG_ENSEMBLE_PROFILE.getPath("fabric-ensemble-" + oldClusterId) + "/org.fusesource.fabric.zookeeper.server-" + oldClusterId + ".properties#" + node);
						addUsedPorts(usedPorts, data);
					}
				}

                Map<String, String> zkConfig = getDataStore().getConfiguration(version, "default", "org.fusesource.fabric.zookeeper");
                if (zkConfig == null) {
                    throw new FabricException("Failed to find old zookeeper configuration in default profile");
                }
				String datas = getSubstitutedData(curator, zkConfig.get("zookeeper.url"));
				for (String data : datas.split(",")) {
					addUsedPorts(usedPorts, data);
				}
			}

			String newClusterId;
			if (oldClusterId == null) {
				newClusterId = "0000";
			} else {
				newClusterId = new DecimalFormat("0000").format(Integer.parseInt(oldClusterId) + 1);
			}

            // create new ensemble
            String ensembleProfile = getDataStore().getProfile(version, "fabric-ensemble-" + newClusterId, true);
            getDataStore().setProfileAttribute(version, ensembleProfile, "abstract", "true");
            getDataStore().setProfileAttribute(version, ensembleProfile, "hidden", "true");

            Properties ensembleProperties = new Properties();
            ensembleProperties.put("tickTime", "2000");
            ensembleProperties.put("initLimit", "10");
            ensembleProperties.put("syncLimit", "5");
            ensembleProperties.put("dataDir", "data/zookeeper/" + newClusterId);

            int index = 1;
			String connectionUrl = "";
			String realConnectionUrl = "";
			String containerList = "";
			for (String container : containers) {
				String ip = getSubstitutedPath(curator, CONTAINER_IP.getPath(container));

				String minimumPort = String.valueOf(Ports.MIN_PORT_NUMBER);
				String maximumPort = String.valueOf(Ports.MAX_PORT_NUMBER);

				if (exists(curator, CONTAINER_PORT_MIN.getPath(container)) != null) {
					minimumPort = getSubstitutedPath(curator, CONTAINER_PORT_MIN.getPath(container));
				}

				if (exists(curator, CONTAINER_PORT_MAX.getPath(container)) != null) {
					maximumPort = getSubstitutedPath(curator, CONTAINER_PORT_MAX.getPath(container));
				}

                String ensembleMemberPid = "org.fusesource.fabric.zookeeper.server-" + newClusterId + ".properties";
                Properties ensembleMemberProperties = new Properties();

                // configure this server in the ensemble
                String ensembleMemberProfile = getDataStore().getProfile(version, "fabric-ensemble-" + newClusterId + "-" + Integer.toString(index), true);
                getDataStore().setProfileAttribute(version, ensembleMemberProfile, "hidden", "true");
                getDataStore().setProfileAttribute(version, ensembleMemberProfile, "parents", ensembleProfile);

                String port1 = Integer.toString(findPort(usedPorts, ip, mapPortToRange(Ports.DEFAULT_ZOOKEEPER_SERVER_PORT, minimumPort, maximumPort)));
                if (containers.size() > 1) {
                    String port2 = Integer.toString(findPort(usedPorts, ip, mapPortToRange(Ports.DEFAULT_ZOOKEEPER_PEER_PORT, minimumPort, maximumPort)));
                    String port3 = Integer.toString(findPort(usedPorts, ip, mapPortToRange(Ports.DEFAULT_ZOOKEEPER_ELECTION_PORT, minimumPort, maximumPort)));
                    ensembleProperties.put("server." + Integer.toString(index), "${zk:" + container + "/ip}:" + port2 + ":" + port3);
                    ensembleMemberProperties.put("server.id", Integer.toString(index));
                }
                ensembleMemberProperties.put("clientPort", port1);

                getDataStore().setFileConfiguration(version, ensembleMemberProfile, ensembleMemberPid, DataStoreHelpers.toBytes(ensembleMemberProperties));

				if (connectionUrl.length() > 0) {
					connectionUrl += ",";
					realConnectionUrl += ",";
				}
				connectionUrl += "${zk:" + container + "/ip}:" + port1;
				realConnectionUrl += ip + ":" + port1;
				if (containerList.length() > 0) {
					containerList += ",";
				}
				containerList += container;
				index++;
			}

            String ensembleConfigName = "org.fusesource.fabric.zookeeper.server-" + newClusterId + ".properties";
            getDataStore().setFileConfiguration(version, ensembleProfile, ensembleConfigName, DataStoreHelpers.toBytes(ensembleProperties));

            //We want to add the container to the ensemble after all everything is set.
            //This is why we do it outside the the loop.
            index = 1;
            for (String container : containers) {
                // add this container to the ensemble
                add(curator, "/fabric/configs/versions/" + version + "/containers/" + container, "fabric-ensemble-" + newClusterId + "-" + Integer.toString(index));
                index++;
            }

            if (oldClusterId != null) {
                CuratorFramework dst = CuratorFrameworkFactory.builder()
                                                              .retryPolicy(new ExponentialBackoffRetry(500, 5))
                                                              .connectString(getSubstitutedData(curator, realConnectionUrl))
                                                              .connectionTimeoutMs(30000)
                                                              .authorization("digest", ("fabric:" + options.getZookeeperPassword()).getBytes("UTF-8"))
                                                              .build();

				dst.start();
				try {
					dst.getZookeeperClient().blockUntilConnectedOrTimedOut();

					copy(curator, dst, "/fabric/registry");
					copy(curator, dst, "/fabric/authentication");
					copy(curator, dst, "/fabric/configs");

					//Make sure that the alive zndoe is deleted for each container.
					for (String container : containers) {
                        deleteSafe(dst, "/fabric/registry/containers/alive/" + container);
					}

					set(dst, CONFIG_ENSEMBLES.getPath(), newClusterId);
					set(dst, CONFIG_ENSEMBLE.getPath(newClusterId), containerList);

                    set(dst, CONFIG_ENSEMBLE_URL.getPath(), connectionUrl);
                    set(dst, CONFIG_ENSEMBLE_PASSWORD.getPath(), options.getZookeeperPassword());
                    set(curator, CONFIG_ENSEMBLE_URL.getPath(), connectionUrl);
                    set(curator, CONFIG_ENSEMBLE_PASSWORD.getPath(), options.getZookeeperPassword());


                    for (String v : curator.getChildren().forPath("/fabric/configs/versions")) {
                        for (String container : dst.getChildren().forPath("/fabric/configs/versions/" + v + "/containers")) {
                            remove(dst, "/fabric/configs/versions/" + v + "/containers/" + container, "fabric-ensemble-" + oldClusterId + "-.*");
                        }
                        setConfigProperty(dst, "/fabric/configs/versions/" + v + "/profiles/default/org.fusesource.fabric.zookeeper.properties", "zookeeper.password", "${zk:" + CONFIG_ENSEMBLE_PASSWORD.getPath() + "}");
                        setConfigProperty(dst, "/fabric/configs/versions/" + v + "/profiles/default/org.fusesource.fabric.zookeeper.properties", "zookeeper.url", "${zk:" + CONFIG_ENSEMBLE_URL.getPath() + "}");
                        setConfigProperty(dst, "/fabric/configs/versions/" + v + "/profiles/default/org.fusesource.fabric.zookeeper.properties", ENSEMBLE_ID, newClusterId);
                        setConfigProperty(curator, "/fabric/configs/versions/" + v + "/profiles/default/org.fusesource.fabric.zookeeper.properties", "zookeeper.password", "${zk:" + CONFIG_ENSEMBLE_PASSWORD.getPath() + "}");
                        setConfigProperty(curator, "/fabric/configs/versions/" + v + "/profiles/default/org.fusesource.fabric.zookeeper.properties", "zookeeper.url", "${zk:" + CONFIG_ENSEMBLE_URL.getPath() + "}");
                        setConfigProperty(curator, "/fabric/configs/versions/" + v + "/profiles/default/org.fusesource.fabric.zookeeper.properties", ENSEMBLE_ID, newClusterId);
                    }


                } finally {
					dst.close();
				}
			} else {
				setConfigProperty(curator, "/fabric/configs/versions/" + version + "/profiles/default/org.fusesource.fabric.zookeeper.properties", "zookeeper.password", "${zk:" + CONFIG_ENSEMBLE_PASSWORD.getPath() + "}");
				setConfigProperty(curator, "/fabric/configs/versions/" + version + "/profiles/default/org.fusesource.fabric.zookeeper.properties", "zookeeper.url", "${zk:" + CONFIG_ENSEMBLE_URL.getPath() + "}");
			}
		} catch (Exception e) {
			throw new FabricException("Unable to create zookeeper quorum: " + e.getMessage(), e);
		}
	}

	public static String toString(Properties source) throws IOException {
		StringWriter writer = new StringWriter();
		source.store(writer, null);
		return writer.toString();
	}

    public static Properties toProperties(String source) throws IOException {
        Properties rc = new Properties();
        rc.load(new StringReader(source));
        return rc;
    }

    public static Properties getProperties(CuratorFramework curator, String file, Properties defaultValue) throws InterruptedException, KeeperException, IOException {
        try {
            String v = get(curator, file);
            if (v != null) {
                return DataStoreHelpers.toProperties(v);
            } else {
                return defaultValue;
            }
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public void setConfigProperty(CuratorFramework curator, String file, String prop, String value) throws Exception {
        Properties p = getProperties(curator, file, new Properties());
        p.setProperty(prop, value);
        set(curator, file, toString(p));
    }

    private int findPort(Map<String, List<Integer>> usedPorts, String ip, int port) {
        List<Integer> ports = usedPorts.get(ip);
        if (ports == null) {
            ports = new ArrayList<Integer>();
            usedPorts.put(ip, ports);
        }
        for (; ; ) {
            if (!ports.contains(port)) {
                ports.add(port);
                return port;
            }
            port++;
        }
    }

    private void addUsedPorts(Map<String, List<Integer>> usedPorts, String data) {
        String[] parts = data.split(":");
        List<Integer> ports = usedPorts.get(parts[0]);
        if (ports == null) {
            ports = new ArrayList<Integer>();
            usedPorts.put(parts[0], ports);
        }
        for (int i = 1; i < parts.length; i++) {
            ports.add(Integer.parseInt(parts[i]));
        }
    }

    public void addToCluster(List<String> containers) {
		CreateEnsembleOptions options = CreateEnsembleOptions.build();
		options.setZookeeperPassword(fabricService.getZookeeperPassword());
		addToCluster(containers, options);
	}

	/**
	 * Adds the containers to the cluster.
	 *
	 * @param containers
	 */
	@Override
	public void addToCluster(List<String> containers, CreateEnsembleOptions options) {
		try {
			List<String> current = getEnsembleContainers();
			current.addAll(containers);
			createCluster(current, options);
		} catch (Exception e) {
			throw new FabricException("Unable to add containers to fabric ensemble: " + e.getMessage(), e);
		}
	}

	public void removeFromCluster(List<String> containers) {
		CreateEnsembleOptions options = CreateEnsembleOptions.build();
		options.setZookeeperPassword(fabricService.getZookeeperPassword());
		removeFromCluster(containers, options);
	}

	/**
	 * Removes the containers from the cluster.
	 *
	 * @param containers
	 */
	@Override
	public void removeFromCluster(List<String> containers, CreateEnsembleOptions options) {
		try {
			List<String> current = getEnsembleContainers();
			current.removeAll(containers);
			createCluster(current, options);
		} catch (Exception e) {
			throw new FabricException("Unable to remove containers to fabric ensemble: " + e.getMessage(), e);
		}
	}

	/**
     * Adds users to the Zookeeper registry.
     *
     * @param curator
     * @param users
     * @throws KeeperException
     * @throws InterruptedException
     */
    private void addUsersToZookeeper(CuratorFramework curator, Map<String, String> users) throws Exception {
        Pattern p = Pattern.compile("(.+),(.+)");
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("encryption.prefix", "{CRYPT}");
        options.put("encryption.suffix", "{CRYPT}");
        options.put("encryption.enabled", "true");
        options.put("encryption.enabled", "true");
        options.put("encryption.algorithm", "MD5");
        options.put("encryption.encoding", "hexadecimal");
        options.put(BundleContext.class.getName(), bundleContext);
        EncryptionSupport encryptionSupport = new EncryptionSupport(options);
        Encryption encryption = encryptionSupport.getEncryption();

        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, String> entry : users.entrySet()) {
            String user = entry.getKey();
            Matcher m = p.matcher(entry.getValue());
            if (m.matches() && m.groupCount() >= 2) {
                String password = m.group(1).trim();
                if (!password.startsWith(encryptionSupport.getEncryptionPrefix()) || !password.endsWith(encryptionSupport.getEncryptionSuffix())) {
                    password = encryptionSupport.getEncryptionPrefix() + encryption.encryptPassword(m.group(1)).trim() + encryptionSupport.getEncryptionSuffix();
                }
                String role = m.group(2).trim();
                sb.append(user).append("=").append(password).append(",").append(role).append("\n");
            }
        }
        String allUsers = sb.toString();
        createDefault(curator, "/fabric/authentication/users", allUsers);
    }

    /**
     * Sets up the configuration of the Zookeeper server.
     * @param configurationAdmin
     * @param clientPort
     * @param autoImportFrom
     * @throws IOException
     */
    private static void setUpZookeeperServer(ConfigurationAdmin configurationAdmin,int clientPort, String autoImportFrom ) throws IOException {
        Configuration config = configurationAdmin.createFactoryConfiguration("org.fusesource.fabric.zookeeper.server");
        Hashtable<String, Object> properties = new Hashtable<String, Object>();
        if (autoImportFrom != null) {
            populatePropertiesFrom(properties, autoImportFrom + "/fabric/configs/versions/1.0/profiles/default/org.fusesource.fabric.zookeeper.server.properties");
        }
        properties.put("tickTime", "2000");
        properties.put("initLimit", "10");
        properties.put("syncLimit", "5");
        properties.put("dataDir", "data/zookeeper/0000");
        properties.put("clientPort", String.valueOf(clientPort));
        properties.put("fabric.zookeeper.pid", "org.fusesource.fabric.zookeeper.server-0000");
        config.setBundleLocation(null);
        config.update(properties);
    }

    /**
     * Creates a {@link Properties} instance with the {@link CuratorFramework} configuration.
     * @param connectionUrl
     * @param password
     * @param autoImportFrom
     * @return
     * @throws UnsupportedEncodingException
     */
    private static Properties createCuratorFrameworkProperties(String connectionUrl, String password, String autoImportFrom) throws UnsupportedEncodingException {
        Properties properties = new Properties();
        if (autoImportFrom != null) {
            populatePropertiesFrom(properties, autoImportFrom + "/fabric/configs/versions/1.0/profiles/default/org.apache.curator.framework.properties");
        }
        properties.put(ZOOKEEPER_URL, connectionUrl);
        properties.put(RETRY_POLICY_MAX_RETRIES, "10");
        properties.put(RETRY_POLICY_BASE_SLEEP_TIME_MS, "500");
        properties.put(RETRY_POLICY_MAX_SLEEP_TIME_MS, "500");
        properties.put(CONNECTION_TIMEOUT, "5000");
        properties.put(ZOOKEEPER_PASSWORD, password);
        properties.put("fabric.zookeeper.pid", "org.fusesource.fabric.zookeeper");
        return properties;
    }

    /**
     * Sets up the configuration of the Zookeeper client.
     * @param configurationAdmin
     * @param properties
     * @throws IOException
     */
    private static void setupCuratorFramework(ConfigurationAdmin configurationAdmin, Dictionary properties) throws IOException {
        // Update the client configuration
        Configuration config = configurationAdmin.getConfiguration("org.fusesource.fabric.zookeeper");
        config.setBundleLocation(null);
        config.update(properties);
    }

    /**
     * Looks up the OSGi service registry for the {@link IZKClient}.
     * @param timeout
     * @return
     * @throws InterruptedException
     * @throws TimeoutException
     */
    private static CuratorFramework waitForCurator(BundleContext bundleContext, long timeout) throws InterruptedException, TimeoutException {
        ServiceTracker tracker = new ServiceTracker(bundleContext, CuratorFramework.class.getName(), null);
        tracker.open();
        CuratorFramework curator = (CuratorFramework) tracker.waitForService(timeout);
        if (curator == null) {
            throw new IllegalStateException("Timeout waiting for Curator Framework to be registered");
        }
        tracker.close();
        curator.getZookeeperClient().blockUntilConnectedOrTimedOut();
        return curator;
    }
}
