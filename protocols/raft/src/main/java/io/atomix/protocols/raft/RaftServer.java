/*
 * Copyright 2015-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft;

import io.atomix.protocols.raft.cluster.MemberId;
import io.atomix.protocols.raft.cluster.RaftCluster;
import io.atomix.protocols.raft.cluster.RaftMember;
import io.atomix.protocols.raft.impl.DefaultRaftServer;
import io.atomix.protocols.raft.impl.RaftServiceRegistry;
import io.atomix.protocols.raft.protocol.RaftServerProtocol;
import io.atomix.protocols.raft.service.RaftService;
import io.atomix.protocols.raft.storage.RaftStorage;
import io.atomix.protocols.raft.storage.log.RaftLog;
import io.atomix.storage.StorageLevel;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Provides a standalone implementation of the <a href="http://raft.github.io/">Raft consensus algorithm</a>.
 * <p>
 * To create a new server, use the server {@link RaftServer.Builder}. Servers require
 * cluster membership information in order to perform communication. Each server must be provided a local {@link MemberId}
 * to which to bind the internal {@link io.atomix.protocols.raft.protocol.RaftServerProtocol} and a set of addresses
 * for other members in the cluster.
 * <h2>State machines</h2>
 * Underlying each server is a {@link RaftService}. The state machine is responsible for maintaining the state with
 * relation to {@link io.atomix.protocols.raft.RaftCommand}s and {@link io.atomix.protocols.raft.RaftQuery}s submitted
 * to the server by a client. State machines are provided in a factory to allow servers to transition between stateful
 * and stateless states.
 * <pre>
 *   {@code
 *   Address address = new Address("123.456.789.0", 5000);
 *   Collection<Address> members = Arrays.asList(new Address("123.456.789.1", 5000), new Address("123.456.789.2", 5000));
 *
 *   RaftServer server = RaftServer.builder(address)
 *     .withStateMachine(MyStateMachine::new)
 *     .build();
 *   }
 * </pre>
 * Server state machines are responsible for registering {@link io.atomix.protocols.raft.RaftCommand}s which can be
 * submitted to the cluster. Raft relies upon determinism to ensure consistency throughout the cluster, so <em>it is
 * imperative that each server in a cluster have the same state machine with the same commands.</em> State machines are
 * provided to the server as a {@link Supplier factory} to allow servers to {@link RaftMember#promote(RaftMember.Type) transition}
 * between stateful and stateless states.
 * <h2>Storage</h2>
 * As {@link io.atomix.protocols.raft.RaftCommand}s are received by the server, they're written to the Raft
 * {@link RaftLog} and replicated to other members
 * of the cluster. By default, the log is stored on disk, but users can override the default {@link RaftStorage} configuration
 * via {@link RaftServer.Builder#withStorage(RaftStorage)}. Most notably, to configure the storage module to store entries in
 * memory instead of disk, configure the {@link StorageLevel}.
 * <pre>
 * {@code
 * RaftServer server = RaftServer.builder(address)
 *   .withStateMachine(MyStateMachine::new)
 *   .withStorage(Storage.builder()
 *     .withDirectory(new File("logs"))
 *     .withStorageLevel(StorageLevel.DISK)
 *     .build())
 *   .build();
 * }
 * </pre>
 * Servers use the {@code Storage} object to manage the storage of cluster configurations, voting information, and
 * state machine snapshots in addition to logs. See the {@link RaftStorage} documentation for more information.
 * <h2>Bootstrapping the cluster</h2>
 * Once a server has been built, it must either be {@link #bootstrap() bootstrapped} to form a new cluster or
 * {@link #join(MemberId...) joined} to an existing cluster. The simplest way to bootstrap a new cluster is to bootstrap
 * a single server to which additional servers can be joined.
 * <pre>
 *   {@code
 *   CompletableFuture<RaftServer> future = server.bootstrap();
 *   future.thenRun(() -> {
 *     System.out.println("Server bootstrapped!");
 *   });
 *   }
 * </pre>
 * Alternatively, the bootstrapped cluster can include multiple servers by providing an initial configuration to the
 * {@link #bootstrap(MemberId...)} method on each server. When bootstrapping a multi-node cluster, the bootstrap configuration
 * must be identical on all servers for safety.
 * <pre>
 *   {@code
 *     List<Address> cluster = Arrays.asList(
 *       new Address("123.456.789.0", 5000),
 *       new Address("123.456.789.1", 5000),
 *       new Address("123.456.789.2", 5000)
 *     );
 *
 *     CompletableFuture<RaftServer> future = server.bootstrap(cluster);
 *     future.thenRun(() -> {
 *       System.out.println("Cluster bootstrapped");
 *     });
 *   }
 * </pre>
 * <h2>Adding a server to an existing cluster</h2>
 * Once a single- or multi-node cluster has been {@link #bootstrap() bootstrapped}, often times users need to
 * add additional servers to the cluster. For example, some users prefer to bootstrap a single-node cluster and
 * add additional nodes to that server. Servers can join existing bootstrapped clusters using the {@link #join(MemberId...)}
 * method. When joining an existing cluster, the server simply needs to specify at least one reachable server in the
 * existing cluster.
 * <pre>
 *   {@code
 *     RaftServer server = RaftServer.builder(new Address("123.456.789.3", 5000))
 *       .withTransport(NettyTransport.builder().withThreads(4).build())
 *       .build();
 *
 *     List<Address> cluster = Arrays.asList(
 *       new Address("123.456.789.0", 5000),
 *       new Address("123.456.789.1", 5000),
 *       new Address("123.456.789.2", 5000)
 *     );
 *
 *     CompletableFuture<RaftServer> future = server.join(cluster);
 *     future.thenRun(() -> {
 *       System.out.println("Server joined successfully!");
 *     });
 *   }
 * </pre>
 * <h2>Server types</h2>
 * Servers form new clusters and join existing clusters as active Raft voting members by default. However, for
 * large deployments Raft also supports alternative types of nodes which are configured by setting the server
 * {@link RaftMember.Type}. For example, the {@link RaftMember.Type#PASSIVE PASSIVE} server type does not participate
 * directly in the Raft consensus algorithm and instead receives state changes via an asynchronous gossip protocol.
 * This allows passive members to scale sequential reads beyond the typical three- or five-node Raft cluster. The
 * {@link RaftMember.Type#RESERVE RESERVE} server type is a stateless server that can act as a standby to the stateful
 * servers in the cluster, being {@link RaftMember#promote(RaftMember.Type) promoted} to a stateful state when necessary.
 * <p>
 * Server types are defined in the server builder simply by passing the initial {@link RaftMember.Type} to the
 * {@link Builder#withType(RaftMember.Type)} setter:
 * <pre>
 *   {@code
 *   RaftServer server = RaftServer.builder(address)
 *     .withType(Member.Type.PASSIVE)
 *     .withTransport(new NettyTransport())
 *     .build();
 *   }
 * </pre>
 *
 * @see RaftService
 * @see RaftStorage
 */
public interface RaftServer {

  /**
   * Returns a new Raft server builder using the default host:port.
   * <p>
   * The server will be constructed at 0.0.0.0:8700.
   *
   * @return The server builder.
   */
  static Builder newBuilder() {
    return newBuilder(null);
  }

  /**
   * Returns a new Raft server builder.
   * <p>
   * The provided {@link MemberId} is the address to which to bind the server being constructed.
   *
   * @param localMemberId The local node identifier.
   * @return The server builder.
   */
  static Builder newBuilder(MemberId localMemberId) {
    return new DefaultRaftServer.Builder(localMemberId);
  }

  /**
   * Raft server state types.
   * <p>
   * States represent the context of the server's internal state machine. Throughout the lifetime of a server,
   * the server will periodically transition between states based on requests, responses, and timeouts.
   *
   * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
   */
  enum Role {

    /**
     * Represents the state of an inactive server.
     * <p>
     * All servers start in this state and return to this state when {@link #leave() stopped}.
     */
    INACTIVE(false),

    /**
     * Represents the state of a server that is a reserve member of the cluster.
     * <p>
     * Reserve servers only receive notification of leader, term, and configuration changes.
     */
    RESERVE(false),

    /**
     * Represents the state of a server in the process of catching up its log.
     * <p>
     * Upon successfully joining an existing cluster, the server will transition to the passive state and remain there
     * until the leader determines that the server has caught up enough to be promoted to a full member.
     */
    PASSIVE(false),

    /**
     * Represents the state of a server participating in normal log replication.
     * <p>
     * The follower state is a standard Raft state in which the server receives replicated log entries from the leader.
     */
    FOLLOWER(true),

    /**
     * Represents the state of a server attempting to become the leader.
     * <p>
     * When a server in the follower state fails to receive communication from a valid leader for some time period,
     * the follower will transition to the candidate state. During this period, the candidate requests votes from
     * each of the other servers in the cluster. If the candidate wins the election by receiving votes from a majority
     * of the cluster, it will transition to the leader state.
     */
    CANDIDATE(true),

    /**
     * Represents the state of a server which is actively coordinating and replicating logs with other servers.
     * <p>
     * Leaders are responsible for handling and replicating writes from clients. Note that more than one leader can
     * exist at any given time, but Raft guarantees that no two leaders will exist for the same {@link RaftCluster#getTerm()}.
     */
    LEADER(true);

    private final boolean active;

    Role(boolean active) {
      this.active = active;
    }

    /**
     * Returns whether the role is a voting Raft member role.
     *
     * @return whether the role is a voting member
     */
    public boolean active() {
      return active;
    }
  }

  /**
   * Returns the server name.
   * <p>
   * The server name is provided to the server via the {@link Builder#withName(String) builder configuration}.
   * The name is used internally to manage the server's on-disk state. {@link RaftLog Log},
   * {@link io.atomix.protocols.raft.storage.snapshot.SnapshotStore snapshot},
   * and {@link io.atomix.protocols.raft.storage.system.MetaStore configuration} files stored on disk use
   * the server name as the prefix.
   *
   * @return The server name.
   */
  String name();

  /**
   * Returns the server's cluster configuration.
   * <p>
   * The {@link RaftCluster} is representative of the server's current view of the cluster configuration. The first time
   * the server is {@link #bootstrap() started}, the cluster configuration will be initialized using the {@link MemberId}
   * list provided to the server {@link #newBuilder(MemberId) builder}. For {@link StorageLevel#DISK persistent}
   * servers, subsequent starts will result in the last known cluster configuration being loaded from disk.
   * <p>
   * The returned {@link RaftCluster} can be used to modify the state of the cluster to which this server belongs. Note,
   * however, that users need not explicitly {@link RaftCluster#join(MemberId...) join} or {@link RaftCluster#leave() leave} the
   * cluster since starting and stopping the server results in joining and leaving the cluster respectively.
   *
   * @return The server's cluster configuration.
   */
  RaftCluster cluster();

  /**
   * Returns the server role.
   * <p>
   * The initial state of a Raft server is {@link Role#INACTIVE}. Once the server is {@link #bootstrap() started} and
   * until it is explicitly shutdown, the server will be in one of the active states - {@link Role#PASSIVE},
   * {@link Role#FOLLOWER}, {@link Role#CANDIDATE}, or {@link Role#LEADER}.
   *
   * @return The server role.
   */
  Role getRole();

  /**
   * Returns whether the server is the leader.
   *
   * @return whether the server is the leader
   */
  default boolean isLeader() {
    return getRole() == Role.LEADER;
  }

  /**
   * Returns whether the server is a follower.
   *
   * @return whether the server is a follower
   */
  default boolean isFollower() {
    return getRole() == Role.FOLLOWER;
  }

  /**
   * Adds a role change listener.
   *
   * @param listener The role change listener to add.
   */
  void addRoleChangeListener(Consumer<Role> listener);

  /**
   * Removes a role change listener.
   *
   * @param listener The role change listener to remove.
   */
  void removeRoleChangeListener(Consumer<Role> listener);

  /**
   * Bootstraps a single-node cluster.
   * <p>
   * Bootstrapping a single-node cluster results in the server forming a new cluster to which additional servers
   * can be joined.
   * <p>
   * Only {@link RaftMember.Type#ACTIVE} members can be included in a bootstrap configuration. If the local server is
   * not initialized as an active member, it cannot be part of the bootstrap configuration for the cluster.
   * <p>
   * When the cluster is bootstrapped, the local server will be transitioned into the active state and begin
   * participating in the Raft consensus algorithm. When the cluster is first bootstrapped, no leader will exist.
   * The bootstrapped members will elect a leader amongst themselves. Once a cluster has been bootstrapped, additional
   * members may be {@link #join(MemberId...) joined} to the cluster. In the event that the bootstrapped members cannot
   * reach a quorum to elect a leader, bootstrap will continue until successful.
   * <p>
   * It is critical that all servers in a bootstrap configuration be started with the same exact set of members.
   * Bootstrapping multiple servers with different configurations may result in split brain.
   * <p>
   * The {@link CompletableFuture} returned by this method will be completed once the cluster has been bootstrapped,
   * a leader has been elected, and the leader has been notified of the local server's client configurations.
   *
   * @return A completable future to be completed once the cluster has been bootstrapped.
   */
  CompletableFuture<RaftServer> bootstrap();

  /**
   * Bootstraps the cluster using the provided cluster configuration.
   * <p>
   * Bootstrapping the cluster results in a new cluster being formed with the provided configuration. The initial
   * nodes in a cluster must always be bootstrapped. This is necessary to prevent split brain. If the provided
   * configuration is empty, the local server will form a single-node cluster.
   * <p>
   * Only {@link RaftMember.Type#ACTIVE} members can be included in a bootstrap configuration. If the local server is
   * not initialized as an active member, it cannot be part of the bootstrap configuration for the cluster.
   * <p>
   * When the cluster is bootstrapped, the local server will be transitioned into the active state and begin
   * participating in the Raft consensus algorithm. When the cluster is first bootstrapped, no leader will exist.
   * The bootstrapped members will elect a leader amongst themselves. Once a cluster has been bootstrapped, additional
   * members may be {@link #join(MemberId...) joined} to the cluster. In the event that the bootstrapped members cannot
   * reach a quorum to elect a leader, bootstrap will continue until successful.
   * <p>
   * It is critical that all servers in a bootstrap configuration be started with the same exact set of members.
   * Bootstrapping multiple servers with different configurations may result in split brain.
   * <p>
   * The {@link CompletableFuture} returned by this method will be completed once the cluster has been bootstrapped,
   * a leader has been elected, and the leader has been notified of the local server's client configurations.
   *
   * @param cluster The bootstrap cluster configuration.
   * @return A completable future to be completed once the cluster has been bootstrapped.
   */
  CompletableFuture<RaftServer> bootstrap(MemberId... cluster);

  /**
   * Bootstraps the cluster using the provided cluster configuration.
   * <p>
   * Bootstrapping the cluster results in a new cluster being formed with the provided configuration. The initial
   * nodes in a cluster must always be bootstrapped. This is necessary to prevent split brain. If the provided
   * configuration is empty, the local server will form a single-node cluster.
   * <p>
   * Only {@link RaftMember.Type#ACTIVE} members can be included in a bootstrap configuration. If the local server is
   * not initialized as an active member, it cannot be part of the bootstrap configuration for the cluster.
   * <p>
   * When the cluster is bootstrapped, the local server will be transitioned into the active state and begin
   * participating in the Raft consensus algorithm. When the cluster is first bootstrapped, no leader will exist.
   * The bootstrapped members will elect a leader amongst themselves. Once a cluster has been bootstrapped, additional
   * members may be {@link #join(MemberId...) joined} to the cluster. In the event that the bootstrapped members cannot
   * reach a quorum to elect a leader, bootstrap will continue until successful.
   * <p>
   * It is critical that all servers in a bootstrap configuration be started with the same exact set of members.
   * Bootstrapping multiple servers with different configurations may result in split brain.
   * <p>
   * The {@link CompletableFuture} returned by this method will be completed once the cluster has been bootstrapped,
   * a leader has been elected, and the leader has been notified of the local server's client configurations.
   *
   * @param cluster The bootstrap cluster configuration.
   * @return A completable future to be completed once the cluster has been bootstrapped.
   */
  CompletableFuture<RaftServer> bootstrap(Collection<MemberId> cluster);

  /**
   * Joins the cluster.
   * <p>
   * Joining the cluster results in the local server being added to an existing cluster that has already been
   * bootstrapped. The provided configuration will be used to connect to the existing cluster and submit a join
   * request. Once the server has been added to the existing cluster's configuration, the join operation is complete.
   * <p>
   * Any {@link RaftMember.Type type} of server may join a cluster. In order to join a cluster, the provided list of
   * bootstrapped members must be non-empty and must include at least one active member of the cluster. If no member
   * in the configuration is reachable, the server will continue to attempt to join the cluster until successful. If
   * the provided cluster configuration is empty, the returned {@link CompletableFuture} will be completed exceptionally.
   * <p>
   * When the server joins the cluster, the local server will be transitioned into its initial state as defined by
   * the configured {@link RaftMember.Type}. Once the server has joined, it will immediately begin participating in
   * Raft and asynchronous replication according to its configuration.
   * <p>
   * It's important to note that the provided cluster configuration will only be used the first time the server attempts
   * to join the cluster. Thereafter, in the event that the server crashes and is restarted by {@code join}ing the cluster
   * again, the last known configuration will be used assuming the server is configured with persistent storage. Only when
   * the server leaves the cluster will its configuration and log be reset.
   * <p>
   * In order to preserve safety during configuration changes, Raft leaders do not allow concurrent configuration
   * changes. In the event that an existing configuration change (a server joining or leaving the cluster or a
   * member being {@link RaftMember#promote() promoted} or {@link RaftMember#demote() demoted}) is under way, the local
   * server will retry attempts to join the cluster until successful. If the server fails to reach the leader,
   * the join will be retried until successful.
   *
   * @param cluster A collection of cluster member addresses to join.
   * @return A completable future to be completed once the local server has joined the cluster.
   */
  CompletableFuture<RaftServer> join(MemberId... cluster);

  /**
   * Joins the cluster.
   * <p>
   * Joining the cluster results in the local server being added to an existing cluster that has already been
   * bootstrapped. The provided configuration will be used to connect to the existing cluster and submit a join
   * request. Once the server has been added to the existing cluster's configuration, the join operation is complete.
   * <p>
   * Any {@link RaftMember.Type type} of server may join a cluster. In order to join a cluster, the provided list of
   * bootstrapped members must be non-empty and must include at least one active member of the cluster. If no member
   * in the configuration is reachable, the server will continue to attempt to join the cluster until successful. If
   * the provided cluster configuration is empty, the returned {@link CompletableFuture} will be completed exceptionally.
   * <p>
   * When the server joins the cluster, the local server will be transitioned into its initial state as defined by
   * the configured {@link RaftMember.Type}. Once the server has joined, it will immediately begin participating in
   * Raft and asynchronous replication according to its configuration.
   * <p>
   * It's important to note that the provided cluster configuration will only be used the first time the server attempts
   * to join the cluster. Thereafter, in the event that the server crashes and is restarted by {@code join}ing the cluster
   * again, the last known configuration will be used assuming the server is configured with persistent storage. Only when
   * the server leaves the cluster will its configuration and log be reset.
   * <p>
   * In order to preserve safety during configuration changes, Raft leaders do not allow concurrent configuration
   * changes. In the event that an existing configuration change (a server joining or leaving the cluster or a
   * member being {@link RaftMember#promote() promoted} or {@link RaftMember#demote() demoted}) is under way, the local
   * server will retry attempts to join the cluster until successful. If the server fails to reach the leader,
   * the join will be retried until successful.
   *
   * @param cluster A collection of cluster member addresses to join.
   * @return A completable future to be completed once the local server has joined the cluster.
   */
  CompletableFuture<RaftServer> join(Collection<MemberId> cluster);

  /**
   * Promotes the server to leader if possible.
   *
   * @return a future to be completed once the server has been promoted
   */
  CompletableFuture<RaftServer> promote();

  /**
   * Returns a boolean indicating whether the server is running.
   *
   * @return Indicates whether the server is running.
   */
  boolean isRunning();

  /**
   * Shuts down the server without leaving the Raft cluster.
   *
   * @return A completable future to be completed once the server has been shutdown.
   */
  CompletableFuture<Void> shutdown();

  /**
   * Leaves the Raft cluster.
   *
   * @return A completable future to be completed once the server has left the cluster.
   */
  CompletableFuture<Void> leave();

  /**
   * Builds a single-use Raft server.
   * <p>
   * This builder should be used to programmatically configure and construct a new {@link RaftServer} instance.
   * The builder provides methods for configuring all aspects of a Raft server. The {@code RaftServer.Builder}
   * class cannot be instantiated directly. To create a new builder, use one of the
   * {@link RaftServer#newBuilder(MemberId) server builder factory} methods.
   * <pre>
   *   {@code
   *   RaftServer.Builder builder = RaftServer.builder(address);
   *   }
   * </pre>
   * Once the server has been configured, use the {@link #build()} method to build the server instance:
   * <pre>
   *   {@code
   *   RaftServer server = RaftServer.builder(address)
   *     ...
   *     .build();
   *   }
   * </pre>
   * Each server <em>must</em> be configured with a {@link RaftService}. The state machine is the component of the
   * server that stores state and reacts to commands and queries submitted by clients to the cluster. State machines
   * are provided to the server in the form of a state machine {@link Supplier factory} to allow the server to reconstruct
   * its state when necessary.
   * <pre>
   *   {@code
   *   RaftServer server = RaftServer.builder(address)
   *     .withStateMachine(MyStateMachine::new)
   *     .build();
   *   }
   * </pre>
   */
  abstract class Builder implements io.atomix.utils.Builder<RaftServer> {
    private static final Duration DEFAULT_ELECTION_TIMEOUT = Duration.ofMillis(750);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofMillis(250);
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMillis(5000);
    private static final int DEFAULT_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    protected String name;
    protected RaftMember.Type type = RaftMember.Type.ACTIVE;
    protected MemberId localMemberId;
    protected RaftServerProtocol protocol;
    protected RaftStorage storage;
    protected Duration electionTimeout = DEFAULT_ELECTION_TIMEOUT;
    protected Duration heartbeatInterval = DEFAULT_HEARTBEAT_INTERVAL;
    protected Duration sessionTimeout = DEFAULT_SESSION_TIMEOUT;
    protected final RaftServiceRegistry serviceRegistry = new RaftServiceRegistry();
    protected int threadPoolSize = DEFAULT_THREAD_POOL_SIZE;

    protected Builder(MemberId localMemberId) {
      this.localMemberId = checkNotNull(localMemberId, "localMemberId cannot be null");
    }

    /**
     * Sets the server name.
     * <p>
     * The server name is used to
     *
     * @param name The server name.
     * @return The server builder.
     */
    public Builder withName(String name) {
      this.name = checkNotNull(name, "name cannot be null");
      return this;
    }

    /**
     * Sets the initial server member type.
     *
     * @param type The initial server member type.
     * @return The server builder.
     */
    public Builder withType(RaftMember.Type type) {
      this.type = checkNotNull(type, "type cannot be null");
      return this;
    }

    /**
     * Sets the server protocol.
     *
     * @param protocol The server protocol.
     * @return The server builder.
     */
    public Builder withProtocol(RaftServerProtocol protocol) {
      this.protocol = checkNotNull(protocol, "protocol cannot be null");
      return this;
    }

    /**
     * Sets the storage module.
     *
     * @param storage The storage module.
     * @return The Raft server builder.
     * @throws NullPointerException if {@code storage} is null
     */
    public Builder withStorage(RaftStorage storage) {
      this.storage = checkNotNull(storage, "storage cannot be null");
      return this;
    }

    /**
     * Adds a Raft service factory.
     *
     * @param type    The service type name.
     * @param factory The Raft service factory.
     * @return The server builder.
     * @throws NullPointerException if the {@code factory} is {@code null}
     */
    public Builder addService(String type, Supplier<RaftService> factory) {
      serviceRegistry.register(type, factory);
      return this;
    }

    /**
     * Sets the Raft election timeout, returning the Raft configuration for method chaining.
     *
     * @param electionTimeout The Raft election timeout duration.
     * @return The Raft configuration.
     * @throws IllegalArgumentException If the election timeout is not positive
     * @throws NullPointerException     if {@code electionTimeout} is null
     */
    public Builder withElectionTimeout(Duration electionTimeout) {
      checkNotNull(electionTimeout, "electionTimeout cannot be null");
      checkArgument(!electionTimeout.isNegative() && !electionTimeout.isZero(), "electionTimeout must be positive");
      checkArgument(electionTimeout.toMillis() > heartbeatInterval.toMillis(), "electionTimeout must be greater than heartbeatInterval");
      this.electionTimeout = electionTimeout;
      return this;
    }

    /**
     * Sets the Raft heartbeat interval, returning the Raft configuration for method chaining.
     *
     * @param heartbeatInterval The Raft heartbeat interval duration.
     * @return The Raft configuration.
     * @throws IllegalArgumentException If the heartbeat interval is not positive
     * @throws NullPointerException     if {@code heartbeatInterval} is null
     */
    public Builder withHeartbeatInterval(Duration heartbeatInterval) {
      checkNotNull(heartbeatInterval, "heartbeatInterval cannot be null");
      checkArgument(!heartbeatInterval.isNegative() && !heartbeatInterval.isZero(), "sessionTimeout must be positive");
      checkArgument(heartbeatInterval.toMillis() < electionTimeout.toMillis(), "heartbeatInterval must be less than electionTimeout");
      this.heartbeatInterval = heartbeatInterval;
      return this;
    }

    /**
     * Sets the Raft session timeout, returning the Raft configuration for method chaining.
     *
     * @param sessionTimeout The Raft session timeout duration.
     * @return The server builder.
     * @throws IllegalArgumentException If the session timeout is not positive
     * @throws NullPointerException     if {@code sessionTimeout} is null
     */
    public Builder withSessionTimeout(Duration sessionTimeout) {
      checkNotNull(sessionTimeout, "sessionTimeout cannot be null");
      checkArgument(!sessionTimeout.isNegative() && !sessionTimeout.isZero(), "sessionTimeout must be positive");
      checkArgument(sessionTimeout.toMillis() > electionTimeout.toMillis(), "sessionTimeout must be greater than electionTimeout");
      this.sessionTimeout = sessionTimeout;
      return this;
    }

    /**
     * Sets the server thread pool size.
     *
     * @param threadPoolSize The server thread pool size.
     * @return The server builder.
     */
    public Builder withThreadPoolSize(int threadPoolSize) {
      checkArgument(threadPoolSize > 0, "threadPoolSize must be positive");
      this.threadPoolSize = threadPoolSize;
      return this;
    }
  }

}
