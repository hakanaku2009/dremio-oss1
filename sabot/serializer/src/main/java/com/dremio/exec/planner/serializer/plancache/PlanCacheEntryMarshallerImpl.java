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
package com.dremio.exec.planner.serializer.plancache;

import com.dremio.exec.planner.plancache.distributable.DistributedPlanCacheEntry;
import com.dremio.exec.planner.plancache.distributable.PlanCacheEntryMarshaller;
import com.dremio.plan.serialization.PPlanCache;
import java.io.IOException;

public class PlanCacheEntryMarshallerImpl implements PlanCacheEntryMarshaller {

  private final PlanCacheEntrySerializer planCacheEntrySerializer;

  public PlanCacheEntryMarshallerImpl(PlanCacheEntrySerializer planCacheEntrySerializer) {
    this.planCacheEntrySerializer = planCacheEntrySerializer;
  }

  @Override
  public byte[] marshal(DistributedPlanCacheEntry distributedPlanCacheEntry) {
    PPlanCache.PDistributedPlanCacheEntry proto =
        planCacheEntrySerializer.toProto(distributedPlanCacheEntry);
    return proto.toByteArray();
  }

  @Override
  public DistributedPlanCacheEntry unmarshal(byte[] bytes) throws IOException {
    PPlanCache.PDistributedPlanCacheEntry proto =
        PPlanCache.PDistributedPlanCacheEntry.parseFrom(bytes);
    return planCacheEntrySerializer.fromProto(proto);
  }
}
