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
package org.fusesource.fabric.internal.locks;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.easymock.EasyMock;
import org.fusesource.fabric.zookeeper.IZKClient;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LockImplTest {

    @Test
    public void testLockWithoutCompetition() throws KeeperException, InterruptedException {
        String id = "00000001";
        IZKClient zooKeeper = createMock(IZKClient.class);
        String path = "/my/lock";
        LockImpl lock = new LockImpl(null, path);

        expect(zooKeeper.exists(path)).andReturn(new Stat()).once();
        expect(zooKeeper.create(ZkPath.LOCK.getPath(path), CreateMode.EPHEMERAL_SEQUENTIAL)).andReturn("/my/lock/" + id).once();
        expect(zooKeeper.getChildren(path)).andReturn(Arrays.asList(id)).once();
        replay(zooKeeper);
        assertTrue(lock.tryLock(30, TimeUnit.SECONDS));
        verify(zooKeeper);
    }

    @Test
    public void testLockReentrance() throws KeeperException, InterruptedException {
        String id = "00000001";
        IZKClient zooKeeper = createMock(IZKClient.class);
        String path = "/my/lock";
        LockImpl lock = new LockImpl(null, path);

        expect(zooKeeper.exists(path)).andReturn(new Stat()).times(2);
        expect(zooKeeper.create(ZkPath.LOCK.getPath(path), CreateMode.EPHEMERAL_SEQUENTIAL)).andReturn("/my/lock/" + id).once();
        expect(zooKeeper.getChildren(path)).andReturn(Arrays.asList(id)).times(2);
        replay(zooKeeper);
        assertTrue(lock.tryLock(30, TimeUnit.SECONDS));
        assertTrue(lock.tryLock(30, TimeUnit.SECONDS));
        verify(zooKeeper);
    }

    @Test
    public void testLockWithCompetition() throws KeeperException, InterruptedException {
        String id = "00000001";
        String competitor = "00000002";
        IZKClient zooKeeper = createMock(IZKClient.class);
        String path = "/my/lock";
        LockImpl lock = new LockImpl(null, path);

        expect(zooKeeper.exists(path)).andReturn(new Stat()).once();
        expect(zooKeeper.create(ZkPath.LOCK.getPath(path), CreateMode.EPHEMERAL_SEQUENTIAL)).andReturn("/my/lock/" + id).once();
        expect(zooKeeper.getChildren(path)).andReturn(Arrays.asList(id, competitor)).once();
        replay(zooKeeper);
        assertTrue(lock.tryLock(30, TimeUnit.SECONDS));
        verify(zooKeeper);
    }

    @Test
    public void testLockWithCompetitionAndLoose() throws KeeperException, InterruptedException {
        String id = "00000002";
        String competitor = "00000001";
        IZKClient zooKeeper = createMock(IZKClient.class);
        String path = "/my/lock";
        LockImpl lock = new LockImpl(null, path);

        expect(zooKeeper.exists(path)).andReturn(new Stat()).once();
        expect(zooKeeper.create(ZkPath.LOCK.getPath(path), CreateMode.EPHEMERAL_SEQUENTIAL)).andReturn("/my/lock/" + id).once();
        expect(zooKeeper.getChildren(path)).andReturn(Arrays.asList(id, competitor)).anyTimes();
        expect(zooKeeper.exists(eq(path), EasyMock.<Watcher>anyObject())).andReturn(new Stat()).once();
        replay(zooKeeper);
        assertFalse(lock.tryLock(5, TimeUnit.SECONDS));
        verify(zooKeeper);
    }



    @Test
    public void testLockWithCompetitionAndLooseFirstThenWin() throws KeeperException, InterruptedException {
        String id = "00000002";
        String competitor = "00000001";
        IZKClient zooKeeper = createMock(IZKClient.class);
        String path = "/my/lock";
        final LockImpl lock = new LockImpl(null, path);

        expect(zooKeeper.exists(path)).andReturn(new Stat()).once();
        expect(zooKeeper.create(ZkPath.LOCK.getPath(path), CreateMode.EPHEMERAL_SEQUENTIAL)).andReturn("/my/lock/" + id).once();
        expect(zooKeeper.getChildren(path)).andReturn(Arrays.asList(id, competitor)).once();
        expect(zooKeeper.exists(eq(path), EasyMock.<Watcher>anyObject())).andReturn(new Stat()).once();
        expect(zooKeeper.getChildren(path)).andReturn(Arrays.asList(id)).once();
        replay(zooKeeper);
        new Thread(new Runnable() {
            @Override
            public void run() {
                assertTrue(lock.tryLock(10, TimeUnit.SECONDS));
            }
        }).start();

        Thread.sleep(5000);
        synchronized (lock) {
            lock.notifyAll();
        }
        verify(zooKeeper);
    }
}
