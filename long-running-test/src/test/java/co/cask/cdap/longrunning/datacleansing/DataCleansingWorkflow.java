/*
 * Copyright © 2017 Cask Data, Inc.
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

package co.cask.cdap.longrunning.datacleansing;

import co.cask.cdap.api.workflow.AbstractWorkflow;

/**
 * Implements a simple Workflow with to run the DataCleansingMapReduce MapReduce.
 */
public class DataCleansingWorkflow extends AbstractWorkflow {

  @Override
  public void configure() {
    setName("DataCleansingWorkflow");
    setDescription("Workflow that runs the DataCleansingMapReduce");
    addMapReduce("DataCleansingMapReduce");
  }
}
