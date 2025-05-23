/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.process;

import com.google.common.collect.MoreCollectors;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.commons.resources.MoreResources;
import io.airbyte.config.ResourceRequirements;
import io.airbyte.config.TolerationPOJO;
import io.airbyte.metrics.lib.AirbyteMetricsRegistry;
import io.airbyte.metrics.lib.DogStatsDMetricSingleton;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodFluent;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessHandle.Info;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.val;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * A Process abstraction backed by a Kube Pod running in a Kubernetes cluster 'somewhere'. The
 * parent process starting a Kube Pod Process needs to exist within the Kube networking space. This
 * is so the parent process can forward data into the child's stdin and read the child's stdout and
 * stderr streams and copy configuration files over.
 *
 * This is made possible by:
 * <ul>
 * <li>1) An init container that creates 3 named pipes corresponding to stdin, stdout and std err on
 * a shared volume.</li>
 * <li>2) Config files (e.g. config.json, catalog.json etc) are copied from the parent process into
 * a shared volume.</li>
 * <li>3) Redirecting the stdin named pipe to the original image's entrypoint and it's output into
 * the respective named pipes for stdout and stderr.</li>
 * <li>4) Each named pipe has a corresponding side car. Each side car forwards its stream
 * accordingly using socat. e.g. stderr/stdout is forwarded to parent process while input from the
 * parent process is forwarded into stdin.</li>
 * <li>5) The parent process listens on the stdout and stederr sockets for an incoming TCP
 * connection. It also initiates a TCP connection to the child process aka the Kube pod on the
 * specified stdin socket.</li>
 * <li>6) The child process is able to access configuration data via the shared volume. It's inputs
 * and outputs - stdin, stdout and stderr - are forwarded the parent process via the sidecars.</li>
 * <li>7) The main process has its entrypoint wrapped to perform IO redirection and better error
 * handling.</li>
 * <li>8) A heartbeat sidecar checks if the worker that launched the pod is still alive. If not, the
 * pod will fail.</li>
 * </ul>
 * The docker image used for this pod process must expose a AIRBYTE_ENTRYPOINT which contains the
 * entrypoint we will wrap when creating the main container in the pod.
 *
 * See the constructor for more information.
 */

// TODO(Davin): Better test for this. See https://github.com/airbytehq/airbyte/issues/3700.
public class KubePodProcess extends Process implements KubePod {

  private static final Logger LOGGER = LoggerFactory.getLogger(KubePodProcess.class);

  private static final String INIT_CONTAINER_NAME = "init";
  private static final String DEFAULT_MEMORY_REQUEST = "25Mi";
  private static final String DEFAULT_MEMORY_LIMIT = "50Mi";
  private static final ResourceRequirements DEFAULT_SIDECAR_RESOURCES = new ResourceRequirements()
      .withMemoryLimit(DEFAULT_MEMORY_LIMIT).withMemoryRequest(DEFAULT_MEMORY_REQUEST);

  private static final String PIPES_DIR = "/pipes";
  private static final String STDIN_PIPE_FILE = PIPES_DIR + "/stdin";
  private static final String STDOUT_PIPE_FILE = PIPES_DIR + "/stdout";
  private static final String STDERR_PIPE_FILE = PIPES_DIR + "/stderr";
  public static final String CONFIG_DIR = "/config";
  private static final String TERMINATION_DIR = "/termination";
  private static final String TERMINATION_FILE_MAIN = TERMINATION_DIR + "/main";
  private static final String TERMINATION_FILE_CHECK = TERMINATION_DIR + "/check";
  public static final String SUCCESS_FILE_NAME = "FINISHED_UPLOADING";
  public static final String MAIN_CONTAINER_NAME = "main";

  // 143 is the typical SIGTERM exit code.
  private static final int KILLED_EXIT_CODE = 143;
  private static final int STDIN_REMOTE_PORT = 9001;

  private final KubernetesClient fabricClient;
  private final Pod podDefinition;
  // Necessary since it is not possible to retrieve the pod's actual exit code upon termination. This
  // is because the Kube API server does not keep
  // terminated pod history like it does for successful pods.
  // This variable should be set in functions where the pod is forcefully terminated. See
  // getReturnCode() for more info.
  private final AtomicBoolean wasKilled = new AtomicBoolean(false);
  private final AtomicBoolean wasClosed = new AtomicBoolean(false);

  private final OutputStream stdin;
  private InputStream stdout;
  private InputStream stderr;
  private Integer returnCode = null;
  private Long lastStatusCheck = null;

  private final ServerSocket stdoutServerSocket;
  private final Duration statusCheckInterval;
  private final int stdoutLocalPort;
  private final ServerSocket stderrServerSocket;
  private final int stderrLocalPort;
  private final ExecutorService executorService;

  public static String getPodIP(final KubernetesClient client, final String podName, final String namespace) {
    final var pod = client.pods().inNamespace(namespace).withName(podName).get();
    if (pod == null) {
      throw new RuntimeException("Error: unable to find pod!");
    }
    return pod.getStatus().getPodIP();
  }

  private static Container getInit(final boolean usesStdin,
                                   final List<VolumeMount> mainVolumeMounts,
                                   final String busyboxImage) {
    var initEntrypointStr = String.format("mkfifo %s && mkfifo %s", STDOUT_PIPE_FILE, STDERR_PIPE_FILE);

    if (usesStdin) {
      initEntrypointStr = String.format("mkfifo %s && ", STDIN_PIPE_FILE) + initEntrypointStr;
    }

    initEntrypointStr = initEntrypointStr + String.format(" && until [ -f %s ]; do sleep 0.1; done;", SUCCESS_FILE_NAME);

    return new ContainerBuilder()
        .withName(INIT_CONTAINER_NAME)
        .withImage(busyboxImage)
        .withWorkingDir(CONFIG_DIR)
        .withCommand("sh", "-c", initEntrypointStr)
        .withVolumeMounts(mainVolumeMounts)
        .build();
  }

  private static Container getMain(final String image,
                                   final String imagePullPolicy,
                                   final boolean usesStdin,
                                   final String entrypointOverride,
                                   final List<VolumeMount> mainVolumeMounts,
                                   final ResourceRequirements resourceRequirements,
                                   final Map<Integer, Integer> internalToExternalPorts,
                                   final Map<String, String> envMap,
                                   final String[] args)
      throws IOException {
    final var argsStr = String.join(" ", args);
    final var optionalStdin = usesStdin ? String.format("< %s", STDIN_PIPE_FILE) : "";
    final var entrypointOverrideValue = entrypointOverride == null ? "" : StringEscapeUtils.escapeXSI(entrypointOverride);

    // communicates its completion to the heartbeat check via a file and closes itself if the heartbeat
    // fails
    final var mainCommand = MoreResources.readResource("entrypoints/sync/main.sh")
        .replaceAll("TERMINATION_FILE_CHECK", TERMINATION_FILE_CHECK)
        .replaceAll("TERMINATION_FILE_MAIN", TERMINATION_FILE_MAIN)
        .replaceAll("OPTIONAL_STDIN", optionalStdin)
        .replace("ENTRYPOINT_OVERRIDE_VALUE", entrypointOverrideValue) // use replace and not replaceAll to preserve escaping and quoting
        .replaceAll("ARGS", argsStr)
        .replaceAll("STDERR_PIPE_FILE", STDERR_PIPE_FILE)
        .replaceAll("STDOUT_PIPE_FILE", STDOUT_PIPE_FILE);

    final List<ContainerPort> containerPorts = createContainerPortList(internalToExternalPorts);

    final List<EnvVar> envVars = envMap.entrySet().stream()
        .map(entry -> new EnvVar(entry.getKey(), entry.getValue(), null))
        .collect(Collectors.toList());

    final ContainerBuilder containerBuilder = new ContainerBuilder()
        .withName(MAIN_CONTAINER_NAME)
        .withPorts(containerPorts)
        .withImage(image)
        .withImagePullPolicy(imagePullPolicy)
        .withCommand("sh", "-c", mainCommand)
        .withEnv(envVars)
        .withWorkingDir(CONFIG_DIR)
        .withVolumeMounts(mainVolumeMounts);

    final ResourceRequirementsBuilder resourceRequirementsBuilder = getResourceRequirementsBuilder(resourceRequirements);
    if (resourceRequirementsBuilder != null) {
      containerBuilder.withResources(resourceRequirementsBuilder.build());
    }
    return containerBuilder.build();
  }

  public static List<ContainerPort> createContainerPortList(final Map<Integer, Integer> internalToExternalPorts) {
    return internalToExternalPorts.keySet().stream()
        .map(integer -> new ContainerPortBuilder()
            .withContainerPort(integer)
            .build())
        .collect(Collectors.toList());
  }

  public static void copyFilesToKubeConfigVolume(final KubernetesClient client,
                                                 final Pod podDefinition,
                                                 final Map<String, String> files) {
    final List<Map.Entry<String, String>> fileEntries = new ArrayList<>(files.entrySet());

    // copy this file last to indicate that the copy has completed
    fileEntries.add(new AbstractMap.SimpleEntry<>(SUCCESS_FILE_NAME, ""));

    Path tmpFile = null;
    Process proc = null;
    for (final Map.Entry<String, String> file : fileEntries) {
      try {
        tmpFile = Path.of(IOs.writeFileToRandomTmpDir(file.getKey(), file.getValue()));

        LOGGER.info("Uploading file: " + file.getKey());
        final var containerPath = Path.of(CONFIG_DIR + "/" + file.getKey());

        // using kubectl cp directly here, because both fabric and the official kube client APIs have
        // several issues with copying files. See https://github.com/airbytehq/airbyte/issues/8643 for
        // details.
        final String command = String.format("kubectl cp %s %s/%s:%s -c %s", tmpFile, podDefinition.getMetadata().getNamespace(),
            podDefinition.getMetadata().getName(), containerPath, INIT_CONTAINER_NAME);
        LOGGER.info(command);

        proc = Runtime.getRuntime().exec(command);
        LOGGER.info("Waiting for kubectl cp to complete");
        final int exitCode = proc.waitFor();

        if (exitCode != 0) {
          // Copying the success indicator file to the init container causes the container to immediately
          // exit, causing the `kubectl cp` command to exit with code 137. This check ensures that an error is
          // not thrown in this case if the init container exits successfully.
          if (file.getKey().equals(SUCCESS_FILE_NAME) && waitForInitPodToTerminate(client, podDefinition, 5, TimeUnit.MINUTES) == 0) {
            LOGGER.info("Init was successful; ignoring non-zero kubectl cp exit code for success indicator file.");
          } else {
            throw new IOException("kubectl cp failed with exit code " + exitCode);
          }
        }

        LOGGER.info("kubectl cp complete, closing process");
      } catch (final IOException | InterruptedException e) {
        throw new RuntimeException(e);
      } finally {
        if (tmpFile != null) {
          try {
            tmpFile.toFile().delete();
          } catch (final Exception e) {
            LOGGER.info("Caught exception when deleting temp file but continuing to allow process deletion.", e);
          }
        }
        if (proc != null) {
          proc.destroy();
        }
      }
    }
  }

  /**
   * The calls in this function aren't straight-forward due to api limitations. There is no proper way
   * to directly look for containers within a pod or query if a container is in a running state beside
   * checking if the getRunning field is set. We could put this behind an interface, but that seems
   * heavy-handed compared to the 10 lines here.
   */
  private static void waitForInitPodToRun(final KubernetesClient client, final Pod podDefinition) throws InterruptedException {
    LOGGER.info("Waiting for init container to be ready before copying files...");
    client.pods().inNamespace(podDefinition.getMetadata().getNamespace()).withName(podDefinition.getMetadata().getName())
        .waitUntilCondition(p -> p.getStatus().getInitContainerStatuses().size() != 0, 5, TimeUnit.MINUTES);
    LOGGER.info("Init container present..");
    client.pods().inNamespace(podDefinition.getMetadata().getNamespace()).withName(podDefinition.getMetadata().getName())
        .waitUntilCondition(p -> p.getStatus().getInitContainerStatuses().get(0).getState().getRunning() != null, 5, TimeUnit.MINUTES);
    LOGGER.info("Init container ready..");
  }

  /**
   * Waits for the init container to terminate, and returns its exit code.
   */
  private static int waitForInitPodToTerminate(final KubernetesClient client,
                                               final Pod podDefinition,
                                               final long timeUnitsToWait,
                                               final TimeUnit timeUnit)
      throws InterruptedException {
    LOGGER.info("Waiting for init container to terminate before checking exit value...");
    client.pods().inNamespace(podDefinition.getMetadata().getNamespace()).withName(podDefinition.getMetadata().getName())
        .waitUntilCondition(p -> p.getStatus().getInitContainerStatuses().get(0).getState().getTerminated() != null, timeUnitsToWait, timeUnit);
    final int exitValue = client.pods().inNamespace(podDefinition.getMetadata().getNamespace()).withName(podDefinition.getMetadata().getName()).get()
        .getStatus().getInitContainerStatuses().get(0).getState().getTerminated().getExitCode();
    LOGGER.info("Init container terminated with exit value {}.", exitValue);
    return exitValue;
  }

  private Toleration[] buildPodTolerations(final List<TolerationPOJO> tolerations) {
    if (tolerations == null || tolerations.isEmpty()) {
      return null;
    }
    return tolerations.stream().map(workerPodToleration -> new TolerationBuilder()
        .withKey(workerPodToleration.getKey())
        .withEffect(workerPodToleration.getEffect())
        .withOperator(workerPodToleration.getOperator())
        .withValue(workerPodToleration.getValue())
        .build())
        .toArray(Toleration[]::new);
  }

  public KubePodProcess(final boolean isOrchestrator,
                        final String processRunnerHost,
                        final KubernetesClient fabricClient,
                        final Duration statusCheckInterval,
                        final String podName,
                        final String namespace,
                        final String image,
                        final String imagePullPolicy,
                        final int stdoutLocalPort,
                        final int stderrLocalPort,
                        final String kubeHeartbeatUrl,
                        final boolean usesStdin,
                        final Map<String, String> files,
                        final String entrypointOverride,
                        final ResourceRequirements resourceRequirements,
                        final String imagePullSecret,
                        final List<TolerationPOJO> tolerations,
                        final Optional<Map<String, String>> nodeSelectors,
                        final Map<String, String> labels,
                        final String socatImage,
                        final String busyboxImage,
                        final String curlImage,
                        final Map<String, String> envMap,
                        final Map<Integer, Integer> internalToExternalPorts,
                        final String... args)
      throws IOException, InterruptedException {
    this.fabricClient = fabricClient;
    this.statusCheckInterval = statusCheckInterval;
    this.stdoutLocalPort = stdoutLocalPort;
    this.stderrLocalPort = stderrLocalPort;
    this.stdoutServerSocket = new ServerSocket(stdoutLocalPort);
    this.stderrServerSocket = new ServerSocket(stderrLocalPort);
    this.executorService = Executors.newFixedThreadPool(2);
    setupStdOutAndStdErrListeners();

    if (entrypointOverride != null) {
      LOGGER.info("Found entrypoint override: {}", entrypointOverride);
    }

    final Volume pipeVolume = new VolumeBuilder()
        .withName("airbyte-pipes")
        .withNewEmptyDir()
        .endEmptyDir()
        .build();

    final VolumeMount pipeVolumeMount = new VolumeMountBuilder()
        .withName("airbyte-pipes")
        .withMountPath(PIPES_DIR)
        .build();

    final Volume configVolume = new VolumeBuilder()
        .withName("airbyte-config")
        .withNewEmptyDir()
        .withMedium("Memory")
        .endEmptyDir()
        .build();

    final VolumeMount configVolumeMount = new VolumeMountBuilder()
        .withName("airbyte-config")
        .withMountPath(CONFIG_DIR)
        .build();

    final Volume terminationVolume = new VolumeBuilder()
        .withName("airbyte-termination")
        .withNewEmptyDir()
        .endEmptyDir()
        .build();

    final VolumeMount terminationVolumeMount = new VolumeMountBuilder()
        .withName("airbyte-termination")
        .withMountPath(TERMINATION_DIR)
        .build();

    final Container init = getInit(usesStdin, List.of(pipeVolumeMount, configVolumeMount), busyboxImage);
    final Container main = getMain(
        image,
        imagePullPolicy,
        usesStdin,
        entrypointOverride,
        List.of(pipeVolumeMount, configVolumeMount, terminationVolumeMount),
        resourceRequirements,
        internalToExternalPorts,
        envMap,
        args);

    final io.fabric8.kubernetes.api.model.ResourceRequirements sidecarResources = getResourceRequirementsBuilder(DEFAULT_SIDECAR_RESOURCES).build();
    final Container remoteStdin = new ContainerBuilder()
        .withName("remote-stdin")
        .withImage(socatImage)
        .withCommand("sh", "-c", "socat -d TCP-L:9001 STDOUT > " + STDIN_PIPE_FILE)
        .withVolumeMounts(pipeVolumeMount, terminationVolumeMount)
        .withResources(sidecarResources)
        .build();

    final Container relayStdout = new ContainerBuilder()
        .withName("relay-stdout")
        .withImage(socatImage)
        .withCommand("sh", "-c", String.format("cat %s | socat -d - TCP:%s:%s", STDOUT_PIPE_FILE, processRunnerHost, stdoutLocalPort))
        .withVolumeMounts(pipeVolumeMount, terminationVolumeMount)
        .withResources(sidecarResources)
        .build();

    final Container relayStderr = new ContainerBuilder()
        .withName("relay-stderr")
        .withImage(socatImage)
        .withCommand("sh", "-c", String.format("cat %s | socat -d - TCP:%s:%s", STDERR_PIPE_FILE, processRunnerHost, stderrLocalPort))
        .withVolumeMounts(pipeVolumeMount, terminationVolumeMount)
        .withResources(sidecarResources)
        .build();

    // communicates via a file if it isn't able to reach the heartbeating server and succeeds if the
    // main container completes
    final String heartbeatCommand = MoreResources.readResource("entrypoints/sync/check.sh")
        .replaceAll("TERMINATION_FILE_CHECK", TERMINATION_FILE_CHECK)
        .replaceAll("TERMINATION_FILE_MAIN", TERMINATION_FILE_MAIN)
        .replaceAll("HEARTBEAT_URL", kubeHeartbeatUrl);

    final Container callHeartbeatServer = new ContainerBuilder()
        .withName("call-heartbeat-server")
        .withImage(curlImage)
        .withCommand("sh")
        .withArgs("-c", heartbeatCommand)
        .withVolumeMounts(terminationVolumeMount)
        .withResources(sidecarResources)
        .build();

    final List<Container> containers = usesStdin ? List.of(main, remoteStdin, relayStdout, relayStderr, callHeartbeatServer)
        : List.of(main, relayStdout, relayStderr, callHeartbeatServer);

    PodFluent.SpecNested<PodBuilder> podBuilder = new PodBuilder()
        .withApiVersion("v1")
        .withNewMetadata()
        .withName(podName)
        .withLabels(labels)
        .endMetadata()
        .withNewSpec();

    if (isOrchestrator) {
      podBuilder = podBuilder.withServiceAccount("airbyte-admin").withAutomountServiceAccountToken(true);
    }

    final Pod pod = podBuilder.withTolerations(buildPodTolerations(tolerations))
        .withImagePullSecrets(new LocalObjectReference(imagePullSecret)) // An empty string turns this into a no-op setting.
        .withNodeSelector(nodeSelectors.orElse(null))
        .withRestartPolicy("Never")
        .withInitContainers(init)
        .withContainers(containers)
        .withVolumes(pipeVolume, configVolume, terminationVolume)
        .endSpec()
        .build();

    LOGGER.info("Creating pod...");
    val start = System.currentTimeMillis();
    this.podDefinition = fabricClient.pods().inNamespace(namespace).createOrReplace(pod);

    waitForInitPodToRun(fabricClient, podDefinition);

    LOGGER.info("Copying files...");
    copyFilesToKubeConfigVolume(fabricClient, podDefinition, files);

    LOGGER.info("Waiting until pod is ready...");
    // If a pod gets into a non-terminal error state it should be automatically killed by our
    // heartbeating mechanism.
    // This also handles the case where a very short pod already completes before this check completes
    // the first time.
    // This doesn't manage things like pods that are blocked from running for some cluster reason or if
    // the init
    // container got stuck somehow.
    fabricClient.resource(podDefinition).waitUntilCondition(p -> {
      final boolean isReady = Objects.nonNull(p) && Readiness.getInstance().isReady(p);
      return isReady || isTerminal(p);
    }, 20, TimeUnit.MINUTES);
    DogStatsDMetricSingleton.recordTimeGlobal(AirbyteMetricsRegistry.KUBE_POD_PROCESS_CREATE_TIME_MILLISECS, System.currentTimeMillis() - start);

    // allow writing stdin to pod
    LOGGER.info("Reading pod IP...");
    final var podIp = getPodIP(fabricClient, podName, namespace);
    LOGGER.info("Pod IP: {}", podIp);

    if (usesStdin) {
      LOGGER.info("Creating stdin socket...");
      final var socketToDestStdIo = new Socket(podIp, STDIN_REMOTE_PORT);
      this.stdin = socketToDestStdIo.getOutputStream();
    } else {
      LOGGER.info("Using null stdin output stream...");
      this.stdin = NullOutputStream.NULL_OUTPUT_STREAM;
    }
  }

  private void setupStdOutAndStdErrListeners() {
    final var context = MDC.getCopyOfContextMap();
    executorService.submit(() -> {
      MDC.setContextMap(context);
      try {
        LOGGER.info("Creating stdout socket server...");
        final var socket = stdoutServerSocket.accept(); // blocks until connected
        // cat /proc/sys/net/ipv4/tcp_keepalive_time
        // 300
        // cat /proc/sys/net/ipv4/tcp_keepalive_probes
        // 5
        // cat /proc/sys/net/ipv4/tcp_keepalive_intvl
        // 60
        socket.setKeepAlive(true);
        LOGGER.info("Setting stdout...");
        this.stdout = socket.getInputStream();
      } catch (final IOException e) {
        e.printStackTrace(); // todo: propagate exception / join at the end of constructor
      }
    });
    executorService.submit(() -> {
      MDC.setContextMap(context);
      try {
        LOGGER.info("Creating stderr socket server...");
        final var socket = stderrServerSocket.accept(); // blocks until connected
        socket.setKeepAlive(true);
        LOGGER.info("Setting stderr...");
        this.stderr = socket.getInputStream();
      } catch (final IOException e) {
        e.printStackTrace(); // todo: propagate exception / join at the end of constructor
      }
    });
  }

  @Override
  public OutputStream getOutputStream() {
    return this.stdin;
  }

  @Override
  public InputStream getInputStream() {
    return this.stdout;
  }

  @Override
  public InputStream getErrorStream() {
    return this.stderr;
  }

  /**
   * Immediately terminates the Kube Pod backing this process and cleans up IO resources.
   */
  @Override
  public int waitFor() throws InterruptedException {
    final Pod refreshedPod =
        fabricClient.pods().inNamespace(podDefinition.getMetadata().getNamespace()).withName(podDefinition.getMetadata().getName()).get();
    fabricClient.resource(refreshedPod).waitUntilCondition(KubePodProcess::isTerminal, 10, TimeUnit.DAYS);
    wasKilled.set(true);
    return exitValue();
  }

  /**
   * Intended to gracefully clean up after a completed Kube Pod. This should only be called if the
   * process is successful.
   */
  @Override
  public boolean waitFor(final long timeout, final TimeUnit unit) throws InterruptedException {
    return super.waitFor(timeout, unit);
  }

  /**
   * Immediately terminates the Kube Pod backing this process and cleans up IO resources.
   */
  @Override
  public void destroy() {
    LOGGER.info("Destroying Kube process: {}", podDefinition.getMetadata().getName());
    try {
      fabricClient.resource(podDefinition).withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
      wasKilled.set(true);
    } finally {
      close();
      LOGGER.info("Destroyed Kube process: {}", podDefinition.getMetadata().getName());
    }
  }

  @Override
  public Info info() {
    return new KubePodProcessInfo(podDefinition.getMetadata().getName());
  }

  @Override
  public KubePodInfo getInfo() {
    return new KubePodInfo(podDefinition.getMetadata().getNamespace(), podDefinition.getMetadata().getName());
  }

  /**
   * Close all open resource in the opposite order of resource creation.
   *
   * Null checks exist because certain local Kube clusters (e.g. Docker for Desktop) back this
   * implementation with OS processes and resources, which are automatically reaped by the OS.
   */
  private void close() {
    final boolean previouslyClosed = wasClosed.getAndSet(true);

    // short-circuit if close was already called, so we don't re-offer ports multiple times
    // since the offer call is non-atomic
    if (previouslyClosed) {
      return;
    }

    if (this.stdin != null) {
      Exceptions.swallow(this.stdin::close);
    }
    if (this.stdout != null) {
      Exceptions.swallow(this.stdout::close);
    }
    if (this.stderr != null) {
      Exceptions.swallow(this.stderr::close);
    }
    Exceptions.swallow(this.stdoutServerSocket::close);
    Exceptions.swallow(this.stderrServerSocket::close);
    Exceptions.swallow(this.executorService::shutdownNow);

    KubePortManagerSingleton.getInstance().offer(stdoutLocalPort);
    KubePortManagerSingleton.getInstance().offer(stderrLocalPort);

    LOGGER.debug("Closed {}", podDefinition.getMetadata().getName());
  }

  public static boolean isTerminal(final Pod pod) {
    if (pod.getStatus() != null) {
      // Check if "main" container has terminated, as that defines whether the parent process has
      // terminated.
      final ContainerStatus mainContainerStatus = pod.getStatus()
          .getContainerStatuses()
          .stream()
          .filter(containerStatus -> containerStatus.getName().equals(MAIN_CONTAINER_NAME))
          .collect(MoreCollectors.onlyElement());

      return mainContainerStatus.getState() != null && mainContainerStatus.getState().getTerminated() != null;
    } else {
      return false;
    }
  }

  /**
   * This method hits the Kube Api server to retrieve statuses. Most of the complexity here is
   * minimising the api calls for performance.
   */
  private int getReturnCode(final Pod pod) {
    if (returnCode != null) {
      return returnCode;
    }

    // Reuse the last status check result to prevent overloading the Kube Api server.
    if (lastStatusCheck != null && System.currentTimeMillis() - lastStatusCheck < statusCheckInterval.toMillis()) {
      throw new IllegalThreadStateException("Kube pod process has not exited yet.");
    }

    final var name = pod.getMetadata().getName();
    final Pod refreshedPod = fabricClient.pods().inNamespace(pod.getMetadata().getNamespace()).withName(name).get();
    if (refreshedPod == null) {
      if (wasKilled.get()) {
        LOGGER.info("Unable to find pod {} to retrieve exit value. Defaulting to  value {}. This is expected if the job was cancelled.", name,
            KILLED_EXIT_CODE);
        return KILLED_EXIT_CODE;
      }
      // If the pod cannot be found and was not killed, it either means 1) the pod was not created
      // properly 2) this method is incorrectly called.
      throw new RuntimeException("Cannot find pod while trying to retrieve exit code. This probably means the Pod was not correctly created.");
    }

    if (!isTerminal(refreshedPod)) {
      lastStatusCheck = System.currentTimeMillis();
      throw new IllegalThreadStateException("Kube pod process has not exited yet.");
    }

    final ContainerStatus mainContainerStatus = refreshedPod.getStatus().getContainerStatuses()
        .stream()
        .filter(containerStatus -> containerStatus.getName().equals(MAIN_CONTAINER_NAME))
        .collect(MoreCollectors.onlyElement());

    if (mainContainerStatus.getState() == null || mainContainerStatus.getState().getTerminated() == null) {
      throw new IllegalThreadStateException("Main container in kube pod has not terminated yet.");
    }

    returnCode = mainContainerStatus.getState().getTerminated().getExitCode();

    LOGGER.info("Exit code for pod {} is {}", name, returnCode);
    return returnCode;
  }

  @Override
  public int exitValue() {
    // getReturnCode throws IllegalThreadException if the Kube pod has not exited;
    // close() is only called if the Kube pod has terminated.
    final var returnCode = getReturnCode(podDefinition);
    // The OS traditionally handles process resource clean up. Therefore an exit code of 0, also
    // indicates that all kernel resources were shut down.
    // Because this is a custom implementation, manually close all the resources.
    // Further, since the local resources are used to talk to Kubernetes resources, shut local resources
    // down after Kubernetes resources are shut down, regardless of Kube termination status.
    close();
    LOGGER.info("Closed all resources for pod {}", podDefinition.getMetadata().getName());
    return returnCode;
  }

  public static ResourceRequirementsBuilder getResourceRequirementsBuilder(final ResourceRequirements resourceRequirements) {
    if (resourceRequirements != null) {
      final Map<String, Quantity> requestMap = new HashMap<>();
      // if null then use unbounded resource allocation
      if (!com.google.common.base.Strings.isNullOrEmpty(resourceRequirements.getCpuRequest())) {
        requestMap.put("cpu", Quantity.parse(resourceRequirements.getCpuRequest()));
      }
      if (!com.google.common.base.Strings.isNullOrEmpty(resourceRequirements.getMemoryRequest())) {
        requestMap.put("memory", Quantity.parse(resourceRequirements.getMemoryRequest()));
      }
      final Map<String, Quantity> limitMap = new HashMap<>();
      if (!com.google.common.base.Strings.isNullOrEmpty(resourceRequirements.getCpuLimit())) {
        limitMap.put("cpu", Quantity.parse(resourceRequirements.getCpuLimit()));
      }
      if (!com.google.common.base.Strings.isNullOrEmpty(resourceRequirements.getMemoryLimit())) {
        limitMap.put("memory", Quantity.parse(resourceRequirements.getMemoryLimit()));
      }
      return new ResourceRequirementsBuilder()
          .withRequests(requestMap)
          .withLimits(limitMap);
    }
    return null;
  }

}
