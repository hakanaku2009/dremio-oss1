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
package com.dremio.sabot.exec;

import com.dremio.exec.proto.ExecProtos.FragmentHandle;
import com.dremio.sabot.task.AsyncTaskWrapper;
import com.dremio.sabot.task.SchedulingGroup;
import com.google.common.base.Preconditions;
import org.apache.arrow.memory.BufferAllocator;

/*
 * Allows for creating child allocators for the fragment.
 */
public class FragmentTicket implements AutoCloseable {
  private final PhaseTicket phaseTicket;
  private final FragmentHandle handle;
  private final SchedulingGroup<AsyncTaskWrapper> schedulingGroup;
  private boolean closed;
  private long peakSpillableMemoryConsumption;
  private long peakNonSpillableMemoryConsumption;

  public FragmentTicket(
      PhaseTicket phaseTicket,
      FragmentHandle handle,
      SchedulingGroup<AsyncTaskWrapper> schedulingGroup) {
    this.phaseTicket = Preconditions.checkNotNull(phaseTicket, "PhaseTicket should not be null");
    this.handle = handle;
    this.schedulingGroup = schedulingGroup;
    this.peakSpillableMemoryConsumption = 0;
    this.peakNonSpillableMemoryConsumption = 0;
    phaseTicket.reserve(this);
  }

  public BufferAllocator newChildAllocator(String name, long initReservation, long maxAllocation) {
    return phaseTicket.getAllocator().newChildAllocator(name, initReservation, maxAllocation);
  }

  public FragmentHandle getHandle() {
    return handle;
  }

  public SchedulingGroup<AsyncTaskWrapper> getSchedulingGroup() {
    return schedulingGroup;
  }

  boolean isSpillable(BufferAllocator allocator) {
    String name = allocator.getName();
    if ("HashJoinPOPSpill".equals(name)
        || "HashAggregate".equals(name)
        || "ExternalSort".equals(name)) {
      return true;
    }
    return false;
  }

  public void updateMemoryConsumption() {
    BufferAllocator allocator = phaseTicket.getAllocator();
    for (BufferAllocator child : allocator.getChildAllocators()) {
      if (child.getName().contains("frag:")) {
        for (BufferAllocator operatorAllocator : child.getChildAllocators()) {
          long peakMemoryAllocation = operatorAllocator.getPeakMemoryAllocation();
          if (isSpillable(operatorAllocator)) {
            if (peakMemoryAllocation > peakSpillableMemoryConsumption) {
              peakSpillableMemoryConsumption = peakMemoryAllocation;
            }
          } else {
            if (peakMemoryAllocation > peakNonSpillableMemoryConsumption) {
              peakNonSpillableMemoryConsumption = peakMemoryAllocation;
            }
          }
        }
      } else {
        long peakMemoryAllocation = child.getPeakMemoryAllocation();
        if (peakMemoryAllocation > peakNonSpillableMemoryConsumption) {
          peakNonSpillableMemoryConsumption = peakMemoryAllocation;
        }
      }
    }
  }

  @Override
  public void close() throws Exception {
    Preconditions.checkState(!closed, "Trying to close FragmentTicket more than once");
    closed = true;

    phaseTicket.addPeakNonSpillableMemoryAcrossFragments(peakNonSpillableMemoryConsumption);
    phaseTicket.addPeakSpillableMemoryAcrossFragments(peakSpillableMemoryConsumption);
    if (phaseTicket.release(this)) {
      // NB: The query ticket removes itself from the queries clerk when its last phase ticket is
      // removed
      phaseTicket.getQueryTicket().removePhaseTicket(phaseTicket);
    }
  }
}
