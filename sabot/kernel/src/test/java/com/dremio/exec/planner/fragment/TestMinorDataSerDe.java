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
package com.dremio.exec.planner.fragment;

import static org.junit.Assert.assertEquals;

import com.dremio.exec.ExecTest;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.TopN;
import com.dremio.exec.planner.PhysicalPlanReader;
import com.dremio.exec.planner.PhysicalPlanReaderTestFactory;
import com.dremio.exec.proto.CoordExecRPC.FragmentCodec;
import com.dremio.exec.proto.CoordExecRPC.MinorFragmentIndexEndpoint;
import com.dremio.exec.proto.CoordExecRPC.MinorFragmentIndexEndpointList;
import com.google.protobuf.ByteString;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Before;
import org.junit.Test;

public class TestMinorDataSerDe extends ExecTest {
  MinorDataSerDe serDe;

  @Before
  public void setup() {
    PhysicalPlanReader reader = PhysicalPlanReaderTestFactory.defaultPhysicalPlanReader();
    serDe = new MinorDataSerDe(reader, FragmentCodec.SNAPPY);
  }

  @Test
  public void serializeEndPoint() throws Exception {
    MinorFragmentIndexEndpoint in =
        MinorFragmentIndexEndpoint.newBuilder().setMinorFragmentId(16).setEndpointIndex(8).build();

    ByteString buffer = serDe.serialize(in);
    MinorFragmentIndexEndpoint out = serDe.deserializeMinorFragmentIndexEndpoint(buffer);
    assertEquals(in, out);
  }

  @Test
  public void serializeEndPointList() throws Exception {
    List<MinorFragmentIndexEndpoint> list =
        IntStream.range(1, 8)
            .mapToObj(
                x ->
                    MinorFragmentIndexEndpoint.newBuilder()
                        .setMinorFragmentId(x)
                        .setEndpointIndex(x * 2)
                        .build())
            .collect(Collectors.toList());

    MinorFragmentIndexEndpointList in =
        MinorFragmentIndexEndpointList.newBuilder().addAllFrags(list).build();

    ByteString buffer = serDe.serialize(in);
    MinorFragmentIndexEndpointList out = serDe.deserializeMinorFragmentIndexEndpointList(buffer);
    assertEquals(list, out.getFragsList());
  }

  @Test
  public void serializeJson() throws Exception {
    TopN in = new TopN(OpProps.prototype(), null, 10, Collections.emptyList(), true);
    ByteString buffer = serDe.serializeObjectToJson(in);
    TopN out = serDe.deserializeObjectFromJson(TopN.class, buffer);

    assertEquals(in.getLimit(), out.getLimit());
    assertEquals(in.getReverse(), out.getReverse());
    assertEquals(in.getProps().getSchemaHashCode(), out.getProps().getSchemaHashCode());
  }
}
