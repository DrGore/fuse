/*
 * Copyright (C) FuseSource, Inc.
 *   http://fusesource.com
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.fusesource.fabric.git.internal;

import org.apache.curator.framework.CuratorFramework;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.StoredConfig;
import org.fusesource.fabric.git.FabricGitService;
import org.fusesource.fabric.git.GitNode;
import org.fusesource.fabric.groups.ChangeListener;
import org.fusesource.fabric.groups.ClusteredSingletonWatcher;
import org.fusesource.fabric.groups.Group;
import org.fusesource.fabric.groups.ZooKeeperGroupFactory;
import org.linkedin.zookeeper.client.LifecycleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import static org.fusesource.fabric.zookeeper.ZkPath.GIT;
import static org.fusesource.fabric.zookeeper.utils.CuratorUtils.getSubstitutedData;


public class FabricGitServiceImpl implements FabricGitService, LifecycleListener, ChangeListener {

    public static final String DEFAULT_LOCAL_LOCATION = System.getProperty("karaf.data") + File.separator + "git" + File.separator + "fabric";

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricGitServiceImpl.class);

	private Group group;
	private CuratorFramework curator;
	ClusteredSingletonWatcher<GitNode> watcher = new ClusteredSingletonWatcher<GitNode>(GitNode.class);

    public CuratorFramework getCurator() {
        return curator;
    }

    public void setCurator(CuratorFramework curator) {
        this.curator = curator;
    }

    public void init() {
    }

    public void destroy() {
        if (group != null) {
            group.close();
        }
    }

	@Override
	public Git get() throws IOException {
		File localRepo = new File(DEFAULT_LOCAL_LOCATION);
		if (!localRepo.exists() && !localRepo.mkdirs()) {
			throw new IOException("Failed to create local repository");
		}
		try {
			return Git.open(localRepo);
		} catch (RepositoryNotFoundException e) {
			try {
				Git git = Git.init().setDirectory(localRepo).call();
				git.commit().setMessage("First Commit").setCommitter("fabric", "user@fabric").call();
				return git;
			} catch (GitAPIException ex) {
				throw new IOException(ex);
			}
		}
	}


	@Override
	public void changed() {
        String masterUrl = null;
		GitNode[] masters = watcher.masters();
		if (masters != null && masters.length > 0
                && !masters[0].getAgent().equals(System.getProperty("karaf.name"))) {
            masterUrl = masters[0].getUrl();
        }
		try {
			StoredConfig config = get().getRepository().getConfig();
            if (masterUrl != null) {
                config.setString("remote", "origin", "url", getSubstitutedData(curator, masters[0].getUrl()));
                config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            } else {
                config.unsetSection("remote", "origin");
            }
			config.save();
		} catch (Exception e) {
			LOGGER.error("Failed to point origin to the new master.", e);
		}
	}

	@Override
	public void connected() {
		changed();
	}

	@Override
	public void disconnected() {
		changed();
	}

	@Override
	public void onConnected() {
		group = ZooKeeperGroupFactory.create(curator, GIT.getPath());
		group.add(this);
		watcher.start(group);
	}

	@Override
	public void onDisconnected() {
        watcher.stop();
		group.close();
	}
}
