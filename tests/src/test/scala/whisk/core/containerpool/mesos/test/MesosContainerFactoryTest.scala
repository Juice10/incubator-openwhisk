/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool.mesos.test

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestKit
import akka.testkit.TestProbe
import com.adobe.api.platform.runtime.mesos.Bridge
import com.adobe.api.platform.runtime.mesos.DeleteTask
import com.adobe.api.platform.runtime.mesos.Running
import com.adobe.api.platform.runtime.mesos.SubmitTask
import com.adobe.api.platform.runtime.mesos.Subscribe
import com.adobe.api.platform.runtime.mesos.SubscribeComplete
import com.adobe.api.platform.runtime.mesos.TaskDef
import com.adobe.api.platform.runtime.mesos.User
import common.StreamLogging
import org.apache.mesos.v1.Protos.AgentID
import org.apache.mesos.v1.Protos.TaskID
import org.apache.mesos.v1.Protos.TaskInfo
import org.apache.mesos.v1.Protos.TaskState
import org.apache.mesos.v1.Protos.TaskStatus
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import scala.collection.immutable.Map
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.containerpool.ContainerArgsConfig
import whisk.core.containerpool.logging.DockerToActivationLogStore
import whisk.core.entity.ExecManifest.ImageName
import whisk.core.entity.size._
import whisk.core.mesos.MesosConfig
import whisk.core.mesos.MesosContainerFactory
@RunWith(classOf[JUnitRunner])
class MesosContainerFactoryTest
    extends TestKit(ActorSystem("MesosActorSystem"))
    with FlatSpecLike
    with Matchers
    with StreamLogging
    with BeforeAndAfterEach {

  /** Awaits the given future, throws the exception enclosed in Failure. */
  def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result[A](f, timeout)

  implicit val wskConfig =
    new WhiskConfig(Map(invokerCoreShare -> "2", dockerImageTag -> "latest", wskApiHostname -> "apihost") ++ wskApiHost)
  var count = 0
  var lastTaskId: String = null
  def testTaskId() = {
    count += 1
    lastTaskId = "testTaskId" + count
    lastTaskId
  }

  //TODO: adjust this once the invokerCoreShare issue is fixed see #3110
  def cpus() = wskConfig.invokerCoreShare.toInt / 1024.0 //

  val containerArgsConfig =
    new ContainerArgsConfig("net1", Seq("dns1", "dns2"), Map("extra1" -> Set("e1", "e2"), "extra2" -> Set("e3", "e4")))

  override def beforeEach() = {
    stream.reset()
  }
  behavior of "MesosContainerFactory"

  it should "send Subscribe on init" in {
    val wskConfig = new WhiskConfig(Map())
    val mesosConfig = MesosConfig("http://master:5050", None, "*", 0.seconds, true)
    new MesosContainerFactory(
      wskConfig,
      system,
      logging,
      Map("--arg1" -> Set("v1", "v2")),
      containerArgsConfig,
      mesosConfig,
      (system, mesosConfig) => testActor)

    expectMsg(Subscribe)
  }

  it should "send SubmitTask on create" in {
    val mesosConfig = MesosConfig("http://master:5050", None, "*", 0.seconds, true)

    val factory =
      new MesosContainerFactory(
        wskConfig,
        system,
        logging,
        Map("--arg1" -> Set("v1", "v2"), "--arg2" -> Set("v3", "v4"), "other" -> Set("v5", "v6")),
        containerArgsConfig,
        mesosConfig,
        (_, _) => testActor,
        testTaskId)

    expectMsg(Subscribe)
    factory.createContainer(TransactionId.testing, "mesosContainer", ImageName("fakeImage"), false, 1.MB)

    expectMsg(
      SubmitTask(TaskDef(
        lastTaskId,
        "mesosContainer",
        "fakeImage:" + wskConfig.dockerImageTag,
        cpus,
        1,
        List(8080),
        Some(0),
        false,
        User("net1"),
        Map(
          "arg1" -> Set("v1", "v2"),
          "arg2" -> Set("v3", "v4"),
          "other" -> Set("v5", "v6"),
          "dns" -> Set("dns1", "dns2"),
          "extra1" -> Set("e1", "e2"),
          "extra2" -> Set("e3", "e4")),
        Map("__OW_API_HOST" -> wskConfig.wskApiHost))))
  }

  it should "send DeleteTask on destroy" in {
    val mesosConfig = MesosConfig("http://master:5050", None, "*", 0.seconds, true)

    val probe = TestProbe()
    val factory =
      new MesosContainerFactory(
        wskConfig,
        system,
        logging,
        Map("--arg1" -> Set("v1", "v2"), "--arg2" -> Set("v3", "v4"), "other" -> Set("v5", "v6")),
        containerArgsConfig,
        mesosConfig,
        (system, mesosConfig) => probe.testActor,
        testTaskId)

    probe.expectMsg(Subscribe)
    //emulate successful subscribe
    probe.reply(new SubscribeComplete)

    //create the container
    val c = factory.createContainer(TransactionId.testing, "mesosContainer", ImageName("fakeImage"), false, 1.MB)
    probe.expectMsg(
      SubmitTask(TaskDef(
        lastTaskId,
        "mesosContainer",
        "fakeImage:" + wskConfig.dockerImageTag,
        cpus,
        1,
        List(8080),
        Some(0),
        false,
        User("net1"),
        Map(
          "arg1" -> Set("v1", "v2"),
          "arg2" -> Set("v3", "v4"),
          "other" -> Set("v5", "v6"),
          "dns" -> Set("dns1", "dns2"),
          "extra1" -> Set("e1", "e2"),
          "extra2" -> Set("e3", "e4")),
        Map("__OW_API_HOST" -> wskConfig.wskApiHost))))

    //emulate successful task launch
    val taskId = TaskID.newBuilder().setValue(lastTaskId)

    probe.reply(
      Running(
        TaskInfo
          .newBuilder()
          .setName("testTask")
          .setTaskId(taskId)
          .setAgentId(AgentID.newBuilder().setValue("testAgentID"))
          .build(),
        TaskStatus.newBuilder().setTaskId(taskId).setState(TaskState.TASK_RUNNING).build(),
        "agenthost",
        Seq(30000)))
    //wait for container
    val container = await(c)

    //destroy the container
    implicit val tid = TransactionId.testing
    val deleted = container.destroy()
    probe.expectMsg(DeleteTask(lastTaskId))

    probe.reply(TaskStatus.newBuilder().setTaskId(taskId).setState(TaskState.TASK_KILLED).build())

    await(deleted)

  }

  it should "return static message for logs" in {
    val mesosConfig = MesosConfig("http://master:5050", None, "*", 0.seconds, true)

    val probe = TestProbe()
    val factory =
      new MesosContainerFactory(
        wskConfig,
        system,
        logging,
        Map("--arg1" -> Set("v1", "v2"), "--arg2" -> Set("v3", "v4"), "other" -> Set("v5", "v6")),
        new ContainerArgsConfig("bridge", Seq(), Map("extra1" -> Set("e1", "e2"), "extra2" -> Set("e3", "e4"))),
        mesosConfig,
        (system, mesosConfig) => probe.testActor,
        testTaskId)

    probe.expectMsg(Subscribe)
    //emulate successful subscribe
    probe.reply(new SubscribeComplete)

    //create the container
    val c = factory.createContainer(TransactionId.testing, "mesosContainer", ImageName("fakeImage"), false, 1.MB)

    probe.expectMsg(
      SubmitTask(TaskDef(
        lastTaskId,
        "mesosContainer",
        "fakeImage:" + wskConfig.dockerImageTag,
        cpus,
        1,
        List(8080),
        Some(0),
        false,
        Bridge,
        Map(
          "arg1" -> Set("v1", "v2"),
          "arg2" -> Set("v3", "v4"),
          "other" -> Set("v5", "v6"),
          "extra1" -> Set("e1", "e2"),
          "extra2" -> Set("e3", "e4")),
        Map("__OW_API_HOST" -> wskConfig.wskApiHost))))

    //emulate successful task launch
    val taskId = TaskID.newBuilder().setValue(lastTaskId)

    probe.reply(
      Running(
        TaskInfo
          .newBuilder()
          .setName("testTask")
          .setTaskId(taskId)
          .setAgentId(AgentID.newBuilder().setValue("testAgentID"))
          .build(),
        TaskStatus.newBuilder().setTaskId(taskId).setState(TaskState.TASK_RUNNING).build(),
        "agenthost",
        Seq(30000)))
    //wait for container
    val container = await(c)

    implicit val tid = TransactionId.testing
    implicit val m = ActorMaterializer()
    val logs = container
      .logs(1.MB, false)
      .via(DockerToActivationLogStore.toFormattedString)
      .runWith(Sink.seq)
    await(logs)(0) should endWith
    " stdout: Logs are not collected from Mesos containers currently. You can browse the logs for Mesos Task ID testTaskId using the mesos UI at http://master:5050"

  }
}
