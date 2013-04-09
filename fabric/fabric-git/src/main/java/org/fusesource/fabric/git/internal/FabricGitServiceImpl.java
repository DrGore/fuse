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

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.StoredConfig;
import org.fusesource.fabric.git.FabricGitService;
import org.fusesource.fabric.git.GitNode;
import org.fusesource.fabric.groups.ChangeListener;
import org.fusesource.fabric.groups.GroupFactory;
import org.fusesource.fabric.groups.Singleton;
import org.fusesource.fabric.groups.Group;
import org.fusesource.fabric.utils.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

public class FabricGitServiceImpl implements FabricGitService, ChangeListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(FabricGitServiceImpl.class);

    private GroupFactory groupFactory;
	private Group group;
	private Singleton<GitNode> watcher;


    public GroupFactory getGroupFactory() {
        return groupFactory;
    }

    public void setGroupFactory(GroupFactory groupFactory) {
        this.groupFactory = groupFactory;
    }

    public void init() {
        group = groupFactory.createGroup("git");
        watcher = groupFactory.createSingleton(GitNode.class);
        watcher.start(group);
        group.add(this);
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
				Files.writeToFile(new File(localRepo, "README"), "", Charset.forName("UTF-8"));
				git.add().addFilepattern("README").call();
				git.commit().setMessage("First Commit").setCommitter("fabric", "user@fabric").call();
				return git;
			} catch (GitAPIException ex) {
				throw new IOException(ex);
			}
		}
	}


	@Override
	public void changed() {
		Map<String, GitNode> members = watcher.members();
		if (members == null || members.isEmpty()) {
			return;
		}
		try {
			StoredConfig config = get().getRepository().getConfig();
			config.setString("remote", "origin", "url", members.values().iterator().next().getUrl());
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

}
