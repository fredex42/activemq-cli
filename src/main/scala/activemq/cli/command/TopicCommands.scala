/*
 * Copyright 2017 Anton Wierenga
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

package activemq.cli.command

import activemq.cli.ActiveMQCLI
import activemq.cli.util.Console._
import activemq.cli.util.Implicits._
import javax.management.MBeanServerConnection
import javax.management.MBeanServerInvocationHandler
import org.apache.activemq.broker.jmx.BrokerViewMBean
import org.apache.activemq.broker.jmx.TopicViewMBean
import org.springframework.shell.core.annotation.CliAvailabilityIndicator
import org.springframework.shell.core.annotation.CliCommand
import org.springframework.shell.core.annotation.CliOption
import org.springframework.stereotype.Component

@Component
class TopicCommands extends Commands {

  @CliAvailabilityIndicator(Array("add-topic", "remove-topic", "list-topics", "remove-all-topics"))
  def isBrokerAvailable: Boolean = ActiveMQCLI.broker.isDefined

  @CliCommand(value = Array("add-topic"), help = "Adds a topic")
  def addTopic(@CliOption(key = Array("name"), mandatory = true, help = "The Name of the Topic") name: String): String = {
    withBroker((brokerViewMBean: BrokerViewMBean, mBeanServerConnection: MBeanServerConnection) ⇒ {
      validateTopicNotExists(brokerViewMBean, name)
      brokerViewMBean.addTopic(name)
      info(s"Topic '$name' added")
    })
  }

  @CliCommand(value = Array("remove-topic"), help = "Removes a topic")
  def removeTopic(
    @CliOption(key = Array("name"), mandatory = true, help = "The Name of the Topic") name: String,
    @CliOption(key = Array("force"), specifiedDefaultValue = "yes", mandatory = false, help = "No prompt") force: String
  ): String = {
    withBroker((brokerViewMBean: BrokerViewMBean, mBeanServerConnection: MBeanServerConnection) ⇒ {
      validateTopicExists(brokerViewMBean, name)
      confirm(force)
      brokerViewMBean.removeTopic(name)
      info(s"Topic '$name' removed")
    })
  }

  @CliCommand(value = Array("remove-all-topics"), help = "Removes all topics")
  def removeAllQueues(
    @CliOption(key = Array("force"), specifiedDefaultValue = "yes", mandatory = false, help = "No prompt") force: String,
    @CliOption(key = Array("filter"), mandatory = false, help = "The query") filter: String,
    @CliOption(key = Array("dry-run"), specifiedDefaultValue = "yes", mandatory = false, help = "Dry run") dryRun: String,
    @CliOption(key = Array("enqueued"), mandatory = false, help = "Only queues that meet the enqueued filter are listed") enqueued: String,
    @CliOption(key = Array("dequeued"), mandatory = false, help = "Only queues that meet the dequeued filter are listed") dequeued: String
  ): String = {
    withFilteredTopics("removed", force, filter, dryRun, enqueued, dequeued, (topicViewMBean: TopicViewMBean, brokerViewMBean: BrokerViewMBean,
      dryRun: Boolean, enqueued: String, dequeued: String) ⇒ {
      brokerViewMBean.removeTopic(topicViewMBean.getName)
    })
  }

  @CliCommand(value = Array("list-topics"), help = "Displays topics")
  def listTopics(
    @CliOption(key = Array("filter"), mandatory = false, help = "The query") filter: String,
    @CliOption(key = Array("enqueued"), mandatory = false, help = "Only queues that meet the enqueued filter are listed") enqueued: String,
    @CliOption(key = Array("dequeued"), mandatory = false, help = "Only queues that meet the dequeued filter are listed") dequeued: String
  ): String = {
    val headers = List("Topic Name", "Enqueued", "Dequeued")
    withBroker((brokerViewMBean: BrokerViewMBean, mBeanServerConnection: MBeanServerConnection) ⇒ {

      val enqueuedCount = parseFilterParameter(enqueued, "enqueued")
      val dequeuedCount = parseFilterParameter(dequeued, "dequeued")

      val rows = brokerViewMBean.getTopics.filter(objectName ⇒
        if (filter) {
          getDestinationKeyProperty(objectName).toLowerCase.contains(Option(filter).getOrElse("").toLowerCase)
        } else {
          true
        }).par.map({ objectName ⇒
        (MBeanServerInvocationHandler.newProxyInstance(mBeanServerConnection, objectName, classOf[TopicViewMBean], true))
      }).filter(queueViewMBean ⇒ applyFilterParameter(enqueued, queueViewMBean.getEnqueueCount, enqueuedCount) &&
        applyFilterParameter(dequeued, queueViewMBean.getDequeueCount, dequeuedCount))
        .par.map(topicViewMBean ⇒ List(topicViewMBean.getName, topicViewMBean.getEnqueueCount, topicViewMBean.getDequeueCount))
        .seq.sortBy(ActiveMQCLI.Config.getOptionalString(s"command.topics.order.field") match {
          case Some("Enqueued") ⇒ (row: Seq[Any]) ⇒ { "%015d".format(row(headers.indexOf("Enqueued"))).asInstanceOf[String] }
          case Some("Dequeued") ⇒ (row: Seq[Any]) ⇒ { "%015d".format(row(headers.indexOf("Dequeued"))).asInstanceOf[String] }
          case _ ⇒ (row: Seq[Any]) ⇒ { row(0).asInstanceOf[String] }
        })

      if (rows.size > 0) {
        renderTable(rows, headers) + s"\nTotal topics: ${rows.size}"
      } else {
        warn(s"No topics found")
      }
    })
  }

  def withFilteredTopics(action: String, force: String, filter: String, dryRun: Boolean, enqueued: String, dequeued: String,
    callback: (TopicViewMBean, BrokerViewMBean, Boolean, String, String) ⇒ Unit): String = {
    withBroker((brokerViewMBean: BrokerViewMBean, mBeanServerConnection: MBeanServerConnection) ⇒ {

      val enqueuedCount = parseFilterParameter(enqueued, "enqueued")
      val dequeuedCount = parseFilterParameter(dequeued, "dequeued")

      if (!dryRun) confirm(force)
      val rows = brokerViewMBean.getTopics.filter(objectName ⇒
        if (filter) {
          getDestinationKeyProperty(objectName).toLowerCase.contains(Option(filter).getOrElse("").toLowerCase)
        } else {
          true
        }).par.map({ objectName ⇒
        (MBeanServerInvocationHandler.newProxyInstance(mBeanServerConnection, objectName, classOf[TopicViewMBean], true))
      }).filter(queueViewMBean ⇒ applyFilterParameter(enqueued, queueViewMBean.getEnqueueCount, enqueuedCount) &&
        applyFilterParameter(dequeued, queueViewMBean.getDequeueCount, dequeuedCount)).par.map(topicViewMBean ⇒ {
        val topicName = topicViewMBean.getName
        if (dryRun) {
          s"Topic to be ${action}: '${topicName}'"
        } else {
          callback(topicViewMBean, brokerViewMBean, dryRun, enqueued, dequeued)
          s"Topic ${action}: '${topicName}'"
        }
      })
      if (rows.size > 0) {
        val dryRunText = if (dryRun) "to be " else ""
        (rows.seq.sorted :+ s"Total topics ${dryRunText}${action}: ${rows.size}").mkString("\n")
      } else {
        warn(s"No topics found")
      }
    })
  }
}
