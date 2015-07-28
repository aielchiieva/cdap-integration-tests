/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.apps;

import co.cask.cdap.api.data.format.Formats;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.apps.conversion.ConversionTestExample;
import co.cask.cdap.apps.conversion.ConversionTestService;
import co.cask.cdap.client.AdapterClient;
import co.cask.cdap.client.StreamClient;
import co.cask.cdap.client.util.RESTClient;
import co.cask.cdap.common.utils.Tasks;
import co.cask.cdap.proto.AdapterConfig;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.ProgramRunStatus;
import co.cask.cdap.proto.RunRecord;
import co.cask.cdap.template.etl.batch.config.ETLBatchConfig;
import co.cask.cdap.template.etl.common.ETLStage;
import co.cask.cdap.template.etl.common.Properties;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.ServiceManager;
import co.cask.common.http.HttpMethod;
import co.cask.common.http.HttpResponse;
import co.cask.common.http.ObjectResponse;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class StreamToTPFSIntegrationTest extends AudiTestBase {

  private static final Schema BODY_SCHEMA = Schema.recordOf(
    "event",
    Schema.Field.of("ticker", Schema.of(Schema.Type.STRING)),
    Schema.Field.of("num", Schema.of(Schema.Type.INT)),
    Schema.Field.of("price", Schema.of(Schema.Type.DOUBLE)));

  private static final Id.ApplicationTemplate TEMPLATE_ID = Id.ApplicationTemplate.from("ETLBatch");
  private static final Gson GSON = new Gson();

  @Test
  public void testAdapter() throws Exception {

    StreamClient streamClient = getStreamClient();
    Id.Stream stream1 = Id.Stream.from(TEST_NAMESPACE, "stream1");
    streamClient.create(stream1);
    streamClient.sendEvent(stream1, "AAPL|10|500.32");

    final AdapterClient adapterClient = new AdapterClient(getClientConfig(), getRestClient());
    String filesetName = "temp";
    String newFilesetName = filesetName + "TPFS";
    final Id.Adapter adapterId = Id.Adapter.from(TEST_NAMESPACE, "test1");
    final Id.Adapter newAdapterId = Id.Adapter.from(TEST_NAMESPACE, "test2");
    ETLBatchConfig etlBatchConfig = constructETLBatchConfig(filesetName, "TPFSAvro");
    AdapterConfig adapterConfig = new AdapterConfig("description", TEMPLATE_ID.getId(),
                                                    GSON.toJsonTree(etlBatchConfig));
    adapterClient.create(adapterId, adapterConfig);
    adapterClient.start(adapterId);

    etlBatchConfig = constructTPFSETLConfig(filesetName, newFilesetName);
    adapterConfig = new AdapterConfig("description", TEMPLATE_ID.getId(),
                                      GSON.toJsonTree(etlBatchConfig));
    adapterClient.create(newAdapterId, adapterConfig);

    ApplicationManager applicationManager = deployApplication(ConversionTestExample.class);
    ServiceManager serviceManager = applicationManager.getServiceManager("ConversionTestService");
    serviceManager.start();

    Tasks.waitFor(true, new Callable<Boolean>() {
      public Boolean call() throws Exception {
        List<RunRecord> completedRuns =
          adapterClient.getRuns(adapterId, ProgramRunStatus.COMPLETED, 0, Long.MAX_VALUE, null);
        return !completedRuns.isEmpty();
      }
    }, 10, TimeUnit.MINUTES, 1, TimeUnit.SECONDS);

    adapterClient.start(newAdapterId);

    RESTClient restClient = getRestClient();
    HttpResponse response = restClient.execute(HttpMethod.GET, getClientConfig().
      resolveNamespacedURLV3(TEST_NAMESPACE,
                             "apps/ConversionTestExample/services/ConversionTestService/methods/temp?time=1"),
                                               getClientConfig().getAccessToken());
    List<IntegrationTestRecord> responseObject = ObjectResponse.<List<IntegrationTestRecord>>fromJsonBody(
      response, new TypeToken<List<IntegrationTestRecord>>() { }.getType()).getResponseObject();
    Assert.assertEquals("AAPL", responseObject.get(0).getTicker());
    adapterClient.stop(adapterId);

    Tasks.waitFor(true, new Callable<Boolean>() {
      public Boolean call() throws Exception {
        List<RunRecord> completedRuns =
          adapterClient.getRuns(newAdapterId, ProgramRunStatus.COMPLETED, 0, Long.MAX_VALUE, null);
        return !completedRuns.isEmpty();
      }
    }, 10, TimeUnit.MINUTES, 1, TimeUnit.SECONDS);

    response = restClient.execute(HttpMethod.GET, getClientConfig().
         resolveNamespacedURLV3(TEST_NAMESPACE,
                                "apps/ConversionTestExample/services/ConversionTestService/methods/tempTPFS?time=1"),
                                               getClientConfig().getAccessToken());
    responseObject = ObjectResponse.<List<IntegrationTestRecord>>fromJsonBody(
      response, new TypeToken<List<IntegrationTestRecord>>() { }.getType()).getResponseObject();
    Assert.assertEquals("AAPL", responseObject.get(0).getTicker());

    serviceManager.stop();
    adapterClient.delete(adapterId);
    adapterClient.stop(newAdapterId);
    adapterClient.delete(newAdapterId);
  }

  private ETLBatchConfig constructETLBatchConfig(String fileSetName, String sinkType) {
    ETLStage source = new ETLStage("Stream", ImmutableMap.<String, String>builder()
      .put(Properties.Stream.NAME, "stream1")
      .put(Properties.Stream.DURATION, "10m")
      .put(Properties.Stream.DELAY, "0d")
      .put(Properties.Stream.FORMAT, Formats.CSV)
      .put(Properties.Stream.SCHEMA, BODY_SCHEMA.toString())
      .put("format.setting.delimiter", "|")
      .build());
    ETLStage sink = new ETLStage(sinkType,
                                 ImmutableMap.of(Properties.TimePartitionedFileSetDataset.SCHEMA,
                                                 ConversionTestService.EVENT_SCHEMA.toString(),
                                                 Properties.TimePartitionedFileSetDataset.TPFS_NAME, fileSetName));
    ETLStage transform = new ETLStage("Projection", ImmutableMap.of("drop", "headers"));
    return new ETLBatchConfig("* * * * *", source, sink, Lists.newArrayList(transform));
  }

  private ETLBatchConfig constructTPFSETLConfig(String filesetName, String newFilesetName) {
    ETLStage source = new ETLStage("TPFSAvro",
                                   ImmutableMap.of(Properties.TimePartitionedFileSetDataset.SCHEMA,
                                                   ConversionTestService.EVENT_SCHEMA.toString(),
                                                   Properties.TimePartitionedFileSetDataset.TPFS_NAME, filesetName,
                                                   Properties.TimePartitionedFileSetDataset.DELAY, "0d",
                                                   Properties.TimePartitionedFileSetDataset.DURATION, "10m"));
    ETLStage sink = new ETLStage("TPFSAvro",
                                 ImmutableMap.of(Properties.TimePartitionedFileSetDataset.SCHEMA,
                                                 ConversionTestService.EVENT_SCHEMA.toString(),
                                                 Properties.TimePartitionedFileSetDataset.TPFS_NAME,
                                                 newFilesetName));

    ETLStage transform = new ETLStage("Projection", ImmutableMap.<String, String>of());
    return new ETLBatchConfig("* * * * *", source, sink, Lists.newArrayList(transform));
  }

  private class IntegrationTestRecord {
    private long ts;
    private String ticker;
    private double price;
    private int num;

    IntegrationTestRecord(long ts, String ticker, double price, int num) {
      this.ticker = ticker;
      this.ts = ts;
      this.price = price;
      this.num = num;
    }

    public String getTicker() {
      return ticker;
    }
  }
}
