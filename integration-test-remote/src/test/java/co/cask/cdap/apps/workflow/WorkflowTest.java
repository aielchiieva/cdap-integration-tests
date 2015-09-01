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

package co.cask.cdap.apps.workflow;

import co.cask.cdap.api.flow.flowlet.StreamEvent;
import co.cask.cdap.api.workflow.WorkflowToken;
import co.cask.cdap.apps.AudiTestBase;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.utils.Tasks;
import co.cask.cdap.examples.wikipedia.SparkWikipediaAnalyzer;
import co.cask.cdap.examples.wikipedia.TopNMapReduce;
import co.cask.cdap.examples.wikipedia.WikiContentValidatorAndNormalizer;
import co.cask.cdap.examples.wikipedia.WikipediaPipelineApp;
import co.cask.cdap.examples.wikipedia.WikipediaPipelineWorkflow;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.RunRecord;
import co.cask.cdap.proto.WorkflowTokenNodeDetail;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.StreamManager;
import co.cask.cdap.test.TestConfiguration;
import co.cask.cdap.test.WorkflowManager;
import com.google.common.collect.ImmutableMap;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Test for {@link WikipediaPipelineApp}.
 */
public class WorkflowTest extends AudiTestBase {
  @ClassRule
  public static final TestConfiguration CONFIG = new TestConfiguration("explore.enabled", false);

  @Test
  public void test() throws Exception {
    ApplicationManager applicationManager = deployApplication(WikipediaPipelineApp.class);
    // Setup input streams with test data
    createTestData();

    WorkflowManager workflowManager =
      applicationManager.getWorkflowManager(WikipediaPipelineWorkflow.class.getSimpleName());
    // Test with default threshold. Workflow should not proceed beyond first condition.
    testWorkflow(workflowManager);

    // Test with a reduced threshold, so the workflow proceeds beyond the first predicate
    testWorkflow(workflowManager, 1);
  }

  private void createTestData() throws Exception {
    Id.Stream likesStream = Id.Stream.from(Id.Namespace.DEFAULT, "pageTitleStream");
    StreamManager likesStreamManager = getTestManager().getStreamManager(likesStream);
    String like1 = "{\"name\":\"Metallica\",\"id\":\"107926539230502\",\"created_time\":\"2015-06-25T17:14:47+0000\"}";
    String like2 = "{\"name\":\"grunge\",\"id\":\"911679552186992\",\"created_time\":\"2015-07-20T17:37:04+0000\"}";
    likesStreamManager.send(like1);
    likesStreamManager.send(like2);
    Id.Stream rawWikiDataStream = Id.Stream.from(Id.Namespace.DEFAULT, "wikiStream");
    StreamManager rawWikipediaStreamManager = getTestManager().getStreamManager(rawWikiDataStream);
    String data1 = "{\"batchcomplete\":\"\",\"query\":{\"normalized\":[{\"from\":\"metallica\",\"to\":\"Metallica\"}]" +
      ",\"pages\":{\"18787\":{\"pageid\":18787,\"ns\":0,\"title\":\"Metallica\",\"revisions\":[{\"contentformat\":" +
      "\"text/x-wiki\",\"contentmodel\":\"wikitext\",\"*\":\"{{Other uses}}{{pp-semi|small=yes}}{{pp-move-indef|" +
      "small=yes}}{{Use mdy dates|date=April 2013}}{{Infobox musical artist|name = Metallica|image = Metallica at " +
      "The O2 Arena London 2008.jpg|caption = Metallica in [[London]] in 2008. From left to right: [[Kirk Hammett]], " +
      "[[Lars Ulrich]], [[James Hetfield]] and [[Robert Trujillo]]\"}]}}}}";
    String data2 = "{\"batchcomplete\":\"\",\"query\":{\"pages\":{\"51580\":{\"pageid\":51580,\"ns\":0," +
      "\"title\":\"Grunge\",\"revisions\":[{\"contentformat\":\"text/x-wiki\",\"contentmodel\":\"wikitext\"," +
      "\"*\":\"{{About|the music genre}}{{Infobox music genre| name  = Grunge| bgcolor = crimson| color = white| " +
      "stylistic_origins = {{nowrap|[[Alternative rock]], [[hardcore punk]],}} [[Heavy metal music|heavy metal]], " +
      "[[punk rock]], [[hard rock]], [[noise rock]]| cultural_origins  = Mid-1980s, [[Seattle|Seattle, Washington]], " +
      "[[United States]]| instruments = [[Electric guitar]], [[bass guitar]], [[Drum kit|drums]], " +
      "[[Singing|vocals]]| derivatives = [[Post-grunge]], [[nu metal]]| subgenrelist = | subgenres = | fusiongenres" +
      "      = | regional_scenes   = [[Music of Washington (state)|Washington state]]| other_topics      = * " +
      "[[Alternative metal]]* [[Generation X]]* [[Grunge speak|grunge speak hoax]]* [[timeline of alternative " +
      "rock]]}}'''Grunge''' (sometimes referred to as the '''Seattle sound''') is a subgenre of [[alternative rock]]" +
      " that emerged during the mid-1980s in the American state of [[Washington (state)|Washington]], particularly " +
      "in [[Seattle]].  The early grunge movement revolved around Seattle's [[independent record label]] " +
      "[[Sub Pop]], but by the early 1990s its popularity had spread, with grunge acts in California and other " +
      "parts of the U.S. building strong followings and signing major record deals.Grunge became commercially " +
      "successful in the first half of the 1990s, due mainly to the release of [[Nirvana (band)|Nirvana]]'s " +
      "''[[Nevermind]]'', [[Pearl Jam]]'s ''[[Ten (Pearl Jam album)|Ten]]'', [[Soundgarden]]'s " +
      "''[[Badmotorfinger]]'', [[Alice in Chains]]' ''[[Dirt (Alice in Chains album)|Dirt]]'', and " +
      "[[Stone Temple Pilots]]' ''[[Core (Stone Temple Pilots album)|Core]]''.\"}]}}}}";
    rawWikipediaStreamManager.send(data1);
    rawWikipediaStreamManager.send(data2);

    waitForStreamToBePopulated(likesStreamManager, 2);
    waitForStreamToBePopulated(rawWikipediaStreamManager, 2);
  }

  private void waitForStreamToBePopulated(final StreamManager streamManager, int numEvents) throws Exception {
    Tasks.waitFor(numEvents, new Callable<Integer>() {
      @Override
      public Integer call() throws Exception {
        List<StreamEvent> streamEvents = streamManager.getEvents(0, Long.MAX_VALUE, Integer.MAX_VALUE);
        return streamEvents.size();
      }
    }, 10, TimeUnit.SECONDS, 50, TimeUnit.MILLISECONDS);
  }

  private void testWorkflow(WorkflowManager workflowManager) throws Exception {
    testWorkflow(workflowManager, null);
  }

  private void testWorkflow(WorkflowManager workflowManager, @Nullable Integer threshold) throws Exception {
    if (threshold == null) {
      workflowManager.start();
    } else {
      workflowManager.start(ImmutableMap.of("min.pages.threshold", String.valueOf(threshold)));
    }
    workflowManager.waitForFinish(5, TimeUnit.MINUTES);
    String pid = getLatestPid(workflowManager.getHistory());
    WorkflowTokenNodeDetail tokenAtCondition =
      workflowManager.getTokenAtNode(pid, "EnoughDataToProceed", WorkflowToken.Scope.USER, "result");
    boolean conditionResult = Boolean.parseBoolean(tokenAtCondition.getTokenDataAtNode().get("result"));
    if (threshold == null) {
      Assert.assertFalse(conditionResult);
      assertWorkflowToken(workflowManager, pid, false);
    } else {
      Assert.assertTrue(conditionResult);
      assertWorkflowToken(workflowManager, pid, true);
    }
  }

  @Nullable
  private String getLatestPid(List<RunRecord> history) {
    String pid = null;
    long latestStartTime = 0;
    for (RunRecord runRecord : history) {
      // OK to use start ts, since we ensure that the next run begins after the previous run finishes in the test
      if (runRecord.getStartTs() > latestStartTime) {
        latestStartTime = runRecord.getStartTs();
        pid = runRecord.getPid();
      }
    }
    return pid;
  }

  private void assertWorkflowToken(WorkflowManager workflowManager, String pid,
                                   boolean continueConditionSucceeded) throws NotFoundException {
    assertTokenAtPageTitlesMRNode(workflowManager, pid);
    assertTokenAtRawDataMRNode(workflowManager, pid, continueConditionSucceeded);
    assertTokenAtNormalizationMRNode(workflowManager, pid, continueConditionSucceeded);
    assertTokenAtSparkLDANode(workflowManager, pid, continueConditionSucceeded);
    assertTokenAtTopNMRNode(workflowManager, pid, continueConditionSucceeded);
  }

  private void assertTokenAtPageTitlesMRNode(WorkflowManager workflowManager, String pid) throws NotFoundException {
    WorkflowTokenNodeDetail pageTitlesUserTokens = workflowManager.getTokenAtNode(pid, "LikesToDataset", null, null);
    Assert.assertTrue(Boolean.parseBoolean(pageTitlesUserTokens.getTokenDataAtNode().get("result")));
    WorkflowTokenNodeDetail pageTitlesSystemTokens =
      workflowManager.getTokenAtNode(pid, "LikesToDataset", WorkflowToken.Scope.SYSTEM, null);
    Assert.assertEquals(2, Integer.parseInt(pageTitlesSystemTokens.getTokenDataAtNode().get("custom.num.records")));
  }

  private void assertTokenAtRawDataMRNode(WorkflowManager workflowManager, String pid,
                                          boolean continueConditionSucceeded) throws NotFoundException {
    if (!continueConditionSucceeded) {
      return;
    }
    WorkflowTokenNodeDetail rawWikiDataUserTokens =
      workflowManager.getTokenAtNode(pid, "WikiDataToDataset", null, null);
    Assert.assertTrue(Boolean.parseBoolean(rawWikiDataUserTokens.getTokenDataAtNode().get("result")));
    WorkflowTokenNodeDetail rawWikiDataSystemTokens =
      workflowManager.getTokenAtNode(pid, "WikiDataToDataset", WorkflowToken.Scope.SYSTEM, null);
    Assert.assertEquals(2, Integer.parseInt(rawWikiDataSystemTokens.getTokenDataAtNode().get("custom.num.records")));
  }

  private void assertTokenAtNormalizationMRNode(WorkflowManager workflowManager, String pid,
                                                boolean continueConditionSucceeded) throws NotFoundException {
    if (!continueConditionSucceeded) {
      return;
    }
    WorkflowTokenNodeDetail normalizedDataUserTokens =
      workflowManager.getTokenAtNode(pid, WikiContentValidatorAndNormalizer.NAME, null, null);
    Assert.assertTrue(Boolean.parseBoolean(normalizedDataUserTokens.getTokenDataAtNode().get("result")));
    WorkflowTokenNodeDetail normalizedDataSystemTokens =
      workflowManager.getTokenAtNode(pid, WikiContentValidatorAndNormalizer.NAME, WorkflowToken.Scope.SYSTEM, null);
    Assert.assertEquals(2, Integer.parseInt(normalizedDataSystemTokens.getTokenDataAtNode().get("custom.num.records")));
  }

  private void assertTokenAtSparkLDANode(WorkflowManager workflowManager, String pid,
                                         boolean continueConditionSucceeded) throws NotFoundException {
    if (!continueConditionSucceeded) {
      return;
    }
    WorkflowTokenNodeDetail ldaUserTokens =
      workflowManager.getTokenAtNode(pid, SparkWikipediaAnalyzer.NAME, null, null);
    Assert.assertEquals(10, Integer.parseInt(ldaUserTokens.getTokenDataAtNode().get("num.records")));
    Assert.assertTrue(ldaUserTokens.getTokenDataAtNode().containsKey("highest.score.term"));
    Assert.assertTrue(ldaUserTokens.getTokenDataAtNode().containsKey("highest.score.value"));
    WorkflowTokenNodeDetail ldaSystemTokens =
      workflowManager.getTokenAtNode(pid, SparkWikipediaAnalyzer.NAME, WorkflowToken.Scope.SYSTEM, null);
    Assert.assertTrue(ldaSystemTokens.getTokenDataAtNode().isEmpty());
  }

  private void assertTokenAtTopNMRNode(WorkflowManager workflowManager, String pid,
                                       boolean continueConditionSucceeded) throws NotFoundException {
    if (!continueConditionSucceeded) {
      return;
    }
    WorkflowTokenNodeDetail topNUserTokens = workflowManager.getTokenAtNode(pid, TopNMapReduce.NAME, null, null);
    Assert.assertTrue(Boolean.parseBoolean(topNUserTokens.getTokenDataAtNode().get("result")));
    WorkflowTokenNodeDetail topNSystemTokens =
      workflowManager.getTokenAtNode(pid, TopNMapReduce.NAME, WorkflowToken.Scope.SYSTEM, null);
    Assert.assertEquals(10, Integer.parseInt(topNSystemTokens.getTokenDataAtNode().get("custom.num.records")));
  }
}