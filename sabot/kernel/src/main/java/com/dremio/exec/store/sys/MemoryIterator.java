/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.sys;

import com.dremio.common.VM;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.Iterator;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;

public class MemoryIterator implements Iterator<Object> {

  private final NodeEndpoint nodeEndpoint;
  private final BufferAllocator bufferAllocator;
  private boolean beforeFirst = true;

  public MemoryIterator(NodeEndpoint nodeEndpoint, BufferAllocator bufferAllocator) {
    this.nodeEndpoint = nodeEndpoint;
    this.bufferAllocator = bufferAllocator;
  }

  @Override
  public boolean hasNext() {
    return beforeFirst;
  }

  @Override
  public Object next() {
    if (!beforeFirst) {
      throw new IllegalStateException();
    }
    beforeFirst = false;
    final MemoryInfo memoryInfo = new MemoryInfo();

    memoryInfo.hostname = nodeEndpoint.getAddress();
    memoryInfo.fabric_port = nodeEndpoint.getFabricPort();

    final MemoryUsage heapMemoryUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    memoryInfo.heap_current = heapMemoryUsage.getUsed();
    memoryInfo.heap_max = heapMemoryUsage.getMax();

    BufferPoolMXBean directBean = getDirectBean();
    memoryInfo.jvm_direct_current = directBean.getMemoryUsed();

    memoryInfo.direct_current = bufferAllocator.getAllocatedMemory();
    memoryInfo.direct_max = VM.getMaxDirectMemory();
    memoryInfo.node_id = nodeEndpoint.getAddress() + ":" + nodeEndpoint.getFabricPort();
    return memoryInfo;
  }

  /**
   * @return Direct buffer JMX bean
   */
  public static BufferPoolMXBean getDirectBean() {
    List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    for (BufferPoolMXBean b : pools) {
      if (b.getName().equals("direct")) {
        return b;
      }
    }
    throw new IllegalStateException("Unable to find direct buffer bean.  JVM must be too old.");
  }

  public static MemoryPoolMXBean getMetaspaceBean() {
    List<MemoryPoolMXBean> memPool = ManagementFactory.getMemoryPoolMXBeans();
    for (MemoryPoolMXBean b : memPool) {
      if (b.getName().equals("Metaspace")) {
        return b;
      }
    }
    throw new IllegalStateException("Unable to find metaspace buffer bean.  JVM must be too old.");
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }

  public static class MemoryInfo {
    public String node_id;
    public String hostname;
    public long fabric_port;
    public long heap_current;
    public long heap_max;
    public long direct_current;
    public long jvm_direct_current;
    public long direct_max;
  }
}
