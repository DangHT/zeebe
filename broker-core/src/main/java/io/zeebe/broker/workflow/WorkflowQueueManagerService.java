/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.broker.workflow;

import static io.zeebe.broker.logstreams.LogStreamServiceNames.SNAPSHOT_STORAGE_SERVICE;
import static io.zeebe.broker.logstreams.LogStreamServiceNames.logStreamServiceName;
import static io.zeebe.broker.logstreams.processor.StreamProcessorIds.*;
import static io.zeebe.broker.system.SystemServiceNames.ACTOR_SCHEDULER_SERVICE;
import static io.zeebe.broker.workflow.WorkflowQueueServiceNames.*;

import io.zeebe.broker.incident.IncidentStreamProcessorErrorHandler;
import io.zeebe.broker.incident.processor.IncidentStreamProcessor;
import io.zeebe.broker.logstreams.cfg.StreamProcessorCfg;
import io.zeebe.broker.logstreams.processor.StreamProcessorService;
import io.zeebe.broker.system.ConfigurationManager;
import io.zeebe.broker.transport.clientapi.CommandResponseWriter;
import io.zeebe.broker.workflow.processor.DeploymentStreamProcessor;
import io.zeebe.broker.workflow.processor.WorkflowInstanceStreamProcessor;
import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.processor.StreamProcessorController;
import io.zeebe.servicecontainer.*;
import io.zeebe.util.DeferredCommandContext;
import io.zeebe.util.EnsureUtil;
import io.zeebe.util.actor.*;

public class WorkflowQueueManagerService implements Service<WorkflowQueueManager>, WorkflowQueueManager, Actor
{
    protected static final String NAME = "workflow.queue.manager";

    protected final Injector<Dispatcher> sendBufferInjector = new Injector<>();
    protected final Injector<ActorScheduler> actorSchedulerInjector = new Injector<>();

    protected final ServiceGroupReference<LogStream> logStreamsGroupReference = ServiceGroupReference.<LogStream>create()
            .onAdd((name, stream) -> addStream(stream, name))
            .build();

    protected ServiceStartContext serviceContext;
    protected DeferredCommandContext asyncContext;
    protected StreamProcessorCfg streamProcessorCfg;
    protected WorkflowCfg workflowCfg;

    protected ActorReference actorRef;

    public WorkflowQueueManagerService(final ConfigurationManager configurationManager)
    {
        streamProcessorCfg = configurationManager.readEntry("index", StreamProcessorCfg.class);
        workflowCfg = configurationManager.readEntry("workflow", WorkflowCfg.class);
    }

    @Override
    public void startWorkflowQueue(final LogStream logStream)
    {
        EnsureUtil.ensureNotNull("logStream", logStream);

        installDeploymentStreamProcessor(logStream.getLogName());
        installWorkflowStreamProcessor(logStream);
        installIncidentStreamProcessor(logStream);
    }

    private void installDeploymentStreamProcessor(final String logName)
    {
        final ServiceName<StreamProcessorController> streamProcessorServiceName = deploymentStreamProcessorServiceName(logName);
        final String streamProcessorName = streamProcessorServiceName.getName();

        final Dispatcher sendBuffer = sendBufferInjector.getValue();
        final CommandResponseWriter responseWriter = new CommandResponseWriter(sendBuffer);
        final ServiceName<LogStream> logStreamServiceName = logStreamServiceName(logName);

        final DeploymentStreamProcessor deploymentStreamProcessor = new DeploymentStreamProcessor(responseWriter);
        final StreamProcessorService deploymentStreamProcessorService = new StreamProcessorService(
                streamProcessorName,
                DEPLOYMENT_PROCESSOR_ID,
                deploymentStreamProcessor)
                .eventFilter(DeploymentStreamProcessor.eventFilter());

        serviceContext.createService(streamProcessorServiceName, deploymentStreamProcessorService)
                .dependency(logStreamServiceName, deploymentStreamProcessorService.getSourceStreamInjector())
                .dependency(logStreamServiceName, deploymentStreamProcessorService.getTargetStreamInjector())
                .dependency(SNAPSHOT_STORAGE_SERVICE, deploymentStreamProcessorService.getSnapshotStorageInjector())
                .dependency(ACTOR_SCHEDULER_SERVICE, deploymentStreamProcessorService.getActorSchedulerInjector())
                .install();
    }

    private void installWorkflowStreamProcessor(final LogStream logStream)
    {
        final ServiceName<StreamProcessorController> streamProcessorServiceName = workflowInstanceStreamProcessorServiceName(logStream.getLogName());
        final String streamProcessorName = streamProcessorServiceName.getName();

        final Dispatcher sendBuffer = sendBufferInjector.getValue();
        final CommandResponseWriter responseWriter = new CommandResponseWriter(sendBuffer);
        final ServiceName<LogStream> logStreamServiceName = logStreamServiceName(logStream.getLogName());

        final IncidentStreamProcessorErrorHandler errorHandler = new IncidentStreamProcessorErrorHandler(logStream);

        final WorkflowInstanceStreamProcessor workflowInstanceStreamProcessor = new WorkflowInstanceStreamProcessor(
                responseWriter,
                workflowCfg.deploymentCacheSize,
                workflowCfg.payloadCacheSize);

        final StreamProcessorService workflowStreamProcessorService = new StreamProcessorService(
                streamProcessorName,
                WORKFLOW_INSTANCE_PROCESSOR_ID,
                workflowInstanceStreamProcessor)
                .eventFilter(WorkflowInstanceStreamProcessor.eventFilter())
                .errorHandler(errorHandler);

        serviceContext.createService(streamProcessorServiceName, workflowStreamProcessorService)
                .dependency(logStreamServiceName, workflowStreamProcessorService.getSourceStreamInjector())
                .dependency(logStreamServiceName, workflowStreamProcessorService.getTargetStreamInjector())
                .dependency(SNAPSHOT_STORAGE_SERVICE, workflowStreamProcessorService.getSnapshotStorageInjector())
                .dependency(ACTOR_SCHEDULER_SERVICE, workflowStreamProcessorService.getActorSchedulerInjector())
                .install();
    }

    private void installIncidentStreamProcessor(final LogStream logStream)
    {
        final ServiceName<StreamProcessorController> streamProcessorServiceName = incidentStreamProcessorServiceName(logStream.getLogName());
        final String streamProcessorName = streamProcessorServiceName.getName();

        final ServiceName<LogStream> logStreamServiceName = logStreamServiceName(logStream.getLogName());

        final IncidentStreamProcessor incidentStreamProcessor = new IncidentStreamProcessor();

        final StreamProcessorService incidentStreamProcessorService = new StreamProcessorService(
                streamProcessorName,
                INCIDENT_PROCESSOR_ID,
                incidentStreamProcessor)
                .eventFilter(IncidentStreamProcessor.eventFilter());

        serviceContext.createService(streamProcessorServiceName, incidentStreamProcessorService)
                .dependency(logStreamServiceName, incidentStreamProcessorService.getSourceStreamInjector())
                .dependency(logStreamServiceName, incidentStreamProcessorService.getTargetStreamInjector())
                .dependency(SNAPSHOT_STORAGE_SERVICE, incidentStreamProcessorService.getSnapshotStorageInjector())
                .dependency(ACTOR_SCHEDULER_SERVICE, incidentStreamProcessorService.getActorSchedulerInjector())
                .install();
    }

    @Override
    public void start(ServiceStartContext serviceContext)
    {
        this.serviceContext = serviceContext;
        this.asyncContext = new DeferredCommandContext();

        final ActorScheduler actorScheduler = actorSchedulerInjector.getValue();
        actorRef = actorScheduler.schedule(this);
    }

    @Override
    public void stop(ServiceStopContext ctx)
    {
        ctx.run(() ->
        {
            actorRef.close();
        });
    }

    @Override
    public WorkflowQueueManager get()
    {
        return this;
    }

    public Injector<Dispatcher> getSendBufferInjector()
    {
        return sendBufferInjector;
    }

    public ServiceGroupReference<LogStream> getLogStreamsGroupReference()
    {
        return logStreamsGroupReference;
    }

    public Injector<ActorScheduler> getActorSchedulerInjector()
    {
        return actorSchedulerInjector;
    }

    public void addStream(LogStream logStream, ServiceName<LogStream> logStreamServiceName)
    {
        asyncContext.runAsync((r) ->
        {
            startWorkflowQueue(logStream);
        });
    }

    @Override
    public int getPriority(long now)
    {
        return PRIORITY_LOW;
    }

    @Override
    public int doWork() throws Exception
    {
        return asyncContext.doWork();
    }

    @Override
    public String name()
    {
        return NAME;
    }
}
