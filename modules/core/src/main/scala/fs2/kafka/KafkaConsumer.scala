/*
 * Copyright 2018-2020 OVO Energy Limited
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package fs2.kafka

import cats.{Foldable, Reducible}
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.implicits._
import fs2.{Chunk, Stream}
import fs2.concurrent.Queue
import fs2.kafka.internal._
import fs2.kafka.internal.converters.collection._
import fs2.kafka.internal.instances._
import fs2.kafka.internal.KafkaConsumerActor._
import fs2.kafka.internal.syntax._
import java.util
import org.apache.kafka.common.{Metric, MetricName, PartitionInfo, TopicPartition}
import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

/**
  * [[KafkaConsumer]] represents a consumer of Kafka records, with the
  * ability to `subscribe` to topics, start a single top-level stream,
  * and optionally control it via the provided [[fiber]] instance.<br>
  * <br>
  * The following top-level streams are provided.<br>
  * <br>
  * - [[stream]] provides a single stream of records, where the order
  *   of records is guaranteed per topic-partition.<br>
  * - [[partitionedStream]] provides a stream with elements as streams
  *   that continually request records for a single partition. Order
  *   is guaranteed per topic-partition, but all assigned partitions
  *   will have to be processed in parallel.<br>
  * <br>
  * For the streams, records are wrapped in [[CommittableConsumerRecord]]s
  * which provide [[CommittableOffset]]s with the ability to commit
  * record offsets to Kafka. For performance reasons, offsets are
  * usually committed in batches using [[CommittableOffsetBatch]].
  * Provided `Pipe`s, like [[commitBatchWithin]] are available for
  * batch committing offsets. If you are not committing offsets to
  * Kafka, you can simply discard the [[CommittableOffset]], and
  * only make use of the record.<br>
  * <br>
  * While it's technically possible to start more than one stream from a
  * single [[KafkaConsumer]], it is generally not recommended as there is
  * no guarantee which stream will receive which records, and there might
  * be an overlap, in terms of duplicate records, between the two streams.
  * If a first stream completes, possibly with error, there's no guarantee
  * the stream has processed all of the records it received, and a second
  * stream from the same [[KafkaConsumer]] might not be able to pick up where
  * the first one left off. Therefore, only create a single top-level stream
  * per [[KafkaConsumer]], and if you want to start a new stream if the first
  * one finishes, let the [[KafkaConsumer]] shutdown and create a new one.
  */
sealed abstract class KafkaConsumer[F[_], K, V] {

  /**
    * Alias for `partitionedStream.parJoinUnbounded`.
    * See [[partitionedStream]] for more information.
    *
    * @note you have to first use `subscribe` to subscribe the consumer
    *       before using this `Stream`. If you forgot to subscribe, there
    *       will be a [[NotSubscribedException]] raised in the `Stream`.
    */
  def stream: Stream[F, CommittableConsumerRecord[F, K, V]]

  /**
    * `Stream` where the elements themselves are `Stream`s which continually
    * request records for a single partition. These `Stream`s will have to be
    * processed in parallel, using `parJoin` or `parJoinUnbounded`. Note that
    * when using `parJoin(n)` and `n` is smaller than the number of currently
    * assigned partitions, then there will be assigned partitions which won't
    * be processed. For that reason, prefer `parJoinUnbounded` and the actual
    * limit will be the number of assigned partitions.<br>
    * <br>
    * If you do not want to process all partitions in parallel, then you
    * can use [[stream]] instead, where records for all partitions are in
    * a single `Stream`.
    *
    * @note you have to first use `subscribe` to subscribe the consumer
    *       before using this `Stream`. If you forgot to subscribe, there
    *       will be a [[NotSubscribedException]] raised in the `Stream`.
    */
  def partitionedStream: Stream[F, Stream[F, CommittableConsumerRecord[F, K, V]]]

  /**
    * Returns the set of partitions currently assigned to this consumer.
    */
  def assignment: F[SortedSet[TopicPartition]]

  /**
    * `Stream` where the elements are the set of `TopicPartition`s currently
    * assigned to this consumer. The stream emits whenever a rebalance changes
    * partition assignments.
    */
  def assignmentStream: Stream[F, SortedSet[TopicPartition]]

  /**
    * Overrides the fetch offsets that the consumer will use when reading the
    * next record. If this API is invoked for the same partition more than once,
    * the latest offset will be used. Note that you may lose data if this API is
    * arbitrarily used in the middle of consumption to reset the fetch offsets.
    */
  def seek(partition: TopicPartition, offset: Long): F[Unit]

  /**
    * Seeks to the first offset for each currently assigned partition.
    * This is equivalent to using `seekToBeginning` with an empty set
    * of partitions.<br>
    * <br>
    * Note that this seek evaluates lazily, and only on the next call
    * to `poll` or `position`.
    */
  def seekToBeginning: F[Unit]

  /**
    * Seeks to the first offset for each of the specified partitions.
    * If no partitions are provided, seeks to the first offset for
    * all currently assigned partitions.<br>
    * <br>
    * Note that this seek evaluates lazily, and only on the next call
    * to `poll` or `position`.
    */
  def seekToBeginning[G[_]](partitions: G[TopicPartition])(
    implicit G: Foldable[G]
  ): F[Unit]

  /**
    * Seeks to the last offset for each currently assigned partition.
    * This is equivalent to using `seekToEnd` with an empty set of
    * partitions.<br>
    * <br>
    * Note that this seek evaluates lazily, and only on the next call
    * to `poll` or `position`.
    */
  def seekToEnd: F[Unit]

  /**
    * Seeks to the last offset for each of the specified partitions.
    * If no partitions are provided, seeks to the last offset for
    * all currently assigned partitions.<br>
    * <br>
    * Note that this seek evaluates lazily, and only on the next call
    * to `poll` or `position`.
    */
  def seekToEnd[G[_]](partitions: G[TopicPartition])(
    implicit G: Foldable[G]
  ): F[Unit]

  /**
    * Returns the offset of the next record that will be fetched.<br>
    * <br>
    * Timeout is determined by `default.api.timeout.ms`, which
    * is set using [[ConsumerSettings#withDefaultApiTimeout]].
    */
  def position(partition: TopicPartition): F[Long]

  /**
    * Returns the offset of the next record that will be fetched.
    */
  def position(partition: TopicPartition, timeout: FiniteDuration): F[Long]

  /**
    * Subscribes the consumer to the specified topics. Note that you have to
    * use one of the `subscribe` functions to subscribe to one or more topics
    * before using any of the provided `Stream`s, or a [[NotSubscribedException]]
    * will be raised in the `Stream`s.
    */
  def subscribeTo(firstTopic: String, remainingTopics: String*): F[Unit]

  /**
    * Subscribes the consumer to the specified topics. Note that you have to
    * use one of the `subscribe` functions to subscribe to one or more topics
    * before using any of the provided `Stream`s, or a [[NotSubscribedException]]
    * will be raised in the `Stream`s.
    *
    * @param topics the topics to which the consumer should subscribe
    */
  def subscribe[G[_]](topics: G[String])(implicit G: Reducible[G]): F[Unit]

  /**
    * Subscribes the consumer to the topics matching the specified `Regex`.
    * Note that you have to use one of the `subscribe` functions before you
    * can use any of the provided `Stream`s, or a [[NotSubscribedException]]
    * will be raised in the `Stream`s.
    *
    * @param regex the regex to which matching topics should be subscribed
    */
  def subscribe(regex: Regex): F[Unit]

  /**
    * Unsubscribes the consumer from all topics and partitions assigned
    * by `subscribe` or `assign`.
    */
  def unsubscribe: F[Unit]

  /**
    * Manually assigns the specified list of topic partitions to the consumer.
    * This function does not allow for incremental assignment and will replace
    * the previous assignment (if there is one).
    *
    * Manual topic assignment through this method does not use the consumer's
    * group management functionality. As such, there will be no rebalance
    * operation triggered when group membership or cluster and topic metadata
    * change. Note that it is not possible to use both manual partition
    * assignment with `assign` and group assigment with `subscribe`.
    *
    * If auto-commit is enabled, an async commit (based on the old assignment)
    * will be triggered before the new assignment replaces the old one.
    *
    * To unassign all partitions, use [[KafkaConsumer#unsubscribe]].
    *
    * @see org.apache.kafka.clients.consumer.KafkaConsumer#assign
    */
  def assign(partitions: NonEmptySet[TopicPartition]): F[Unit]

  /**
    * Manually assigns the specified list of partitions for the specified topic
    * to the consumer. This function does not allow for incremental assignment
    * and will replace the previous assignment (if there is one).
    *
    * Manual topic assignment through this method does not use the consumer's
    * group management functionality. As such, there will be no rebalance
    * operation triggered when group membership or cluster and topic metadata
    * change. Note that it is not possible to use both manual partition
    * assignment with `assign` and group assignment with `subscribe`.
    *
    * If auto-commit is enabled, an async commit (based on the old assignment)
    * will be triggered before the new assignment replaces the old one.
    *
    * To unassign all partitions, use [[KafkaConsumer#unsubscribe]].
    *
    * @see org.apache.kafka.clients.consumer.KafkaConsumer#assign
    */
  def assign(topic: String, partitions: NonEmptySet[Int]): F[Unit]

  /**
    * Manually assigns all partitions for the specified topic to the consumer.
    */
  def assign(topic: String): F[Unit]

  /**
    * Returns the partitions for the specified topic.
    *
    * Timeout is determined by `default.api.timeout.ms`, which
    * is set using [[ConsumerSettings#withDefaultApiTimeout]].
    */
  def partitionsFor(topic: String): F[List[PartitionInfo]]

  /**
    * Returns the partitions for the specified topic.
    */
  def partitionsFor(topic: String, timeout: FiniteDuration): F[List[PartitionInfo]]

  /**
    * Returns the first offset for the specified partitions.<br>
    * <br>
    * Timeout is determined by `default.api.timeout.ms`, which
    * is set using [[ConsumerSettings#withDefaultApiTimeout]].
    */
  def beginningOffsets(
    partitions: Set[TopicPartition]
  ): F[Map[TopicPartition, Long]]

  /**
    * Returns the first offset for the specified partitions.
    */
  def beginningOffsets(
    partitions: Set[TopicPartition],
    timeout: FiniteDuration
  ): F[Map[TopicPartition, Long]]

  /**
    * Returns the last offset for the specified partitions.<br>
    * <br>
    * Timeout is determined by `request.timeout.ms`, which
    * is set using [[ConsumerSettings#withRequestTimeout]].
    */
  def endOffsets(
    partitions: Set[TopicPartition]
  ): F[Map[TopicPartition, Long]]

  /**
    * Returns the last offset for the specified partitions.
    */
  def endOffsets(
    partitions: Set[TopicPartition],
    timeout: FiniteDuration
  ): F[Map[TopicPartition, Long]]

  /**
    * A `Fiber` that can be used to cancel the underlying consumer, or
    * wait for it to complete. If you're using [[stream]], or any other
    * provided stream in [[KafkaConsumer]], these will be automatically
    * interrupted when the underlying consumer has been cancelled or
    * when it finishes with an exception.<br>
    * <br>
    * Whenever `cancel` is invoked, an attempt will be made to stop the
    * underlying consumer. The `cancel` operation will not wait for the
    * consumer to shutdown. If you also want to wait for the shutdown
    * to complete, you can use `join`. Note that `join` is guaranteed
    * to complete after consumer shutdown, even when the consumer is
    * cancelled with `cancel`.<br>
    * <br>
    * This `Fiber` instance is usually only required if the consumer
    * needs to be cancelled due to some external condition, or when an
    * external process needs to be cancelled whenever the consumer has
    * shut down. Most of the time, when you're only using the streams
    * provided by [[KafkaConsumer]], there is no need to use this.
    */
  def fiber: Fiber[F, Unit]

  /**
    * Returns consumer metrics.
    *
    * @see org.apache.kafka.clients.consumer.KafkaConsumer#metrics
    */
  def metrics: F[Map[MetricName, Metric]]
}

private[kafka] object KafkaConsumer {
  private[this] def startConsumerActor[F[_], K, V](
    requests: Queue[F, Request[F, K, V]],
    polls: Queue[F, Request[F, K, V]],
    actor: KafkaConsumerActor[F, K, V]
  )(
    implicit F: Concurrent[F],
    context: ContextShift[F]
  ): Resource[F, Fiber[F, Unit]] =
    Resource.make {
      Deferred[F, Either[Throwable, Unit]].flatMap { deferred =>
        requests.tryDequeue1
          .flatMap(_.map(F.pure).getOrElse(polls.dequeue1))
          .flatMap(actor.handle(_) >> context.shift)
          .foreverM[Unit]
          .guaranteeCase {
            case ExitCase.Error(e) => deferred.complete(Left(e))
            case _                 => deferred.complete(Right(()))
          }
          .start
          .map(fiber => Fiber[F, Unit](deferred.get.rethrow, fiber.cancel.start.void))
      }
    }(_.cancel)

  private[this] def startPollScheduler[F[_], K, V](
    polls: Queue[F, Request[F, K, V]],
    pollInterval: FiniteDuration
  )(
    implicit F: Concurrent[F],
    timer: Timer[F]
  ): Resource[F, Fiber[F, Unit]] =
    Resource.make {
      Deferred[F, Either[Throwable, Unit]].flatMap { deferred =>
        polls
          .enqueue1(Request.poll)
          .flatMap(_ => timer.sleep(pollInterval))
          .foreverM[Unit]
          .guaranteeCase {
            case ExitCase.Error(e) => deferred.complete(Left(e))
            case _                 => deferred.complete(Right(()))
          }
          .start
          .map(fiber => Fiber[F, Unit](deferred.get.rethrow, fiber.cancel.start.void))
      }
    }(_.cancel)

  private[this] def createKafkaConsumer[F[_], K, V](
    requests: Queue[F, Request[F, K, V]],
    settings: ConsumerSettings[F, K, V],
    actor: Fiber[F, Unit],
    polls: Fiber[F, Unit],
    streamIdRef: Ref[F, Int],
    id: Int,
    withConsumer: WithConsumer[F]
  )(implicit F: Concurrent[F]): KafkaConsumer[F, K, V] =
    new KafkaConsumer[F, K, V] {
      override val fiber: Fiber[F, Unit] = {
        val actorFiber =
          Fiber[F, Unit](actor.join.guaranteeCase {
            case ExitCase.Completed => polls.cancel
            case _                  => F.unit
          }, actor.cancel)

        val pollsFiber =
          Fiber[F, Unit](polls.join.guaranteeCase {
            case ExitCase.Completed => actor.cancel
            case _                  => F.unit
          }, polls.cancel)

        actorFiber combine pollsFiber
      }

      override def partitionedStream: Stream[F, Stream[F, CommittableConsumerRecord[F, K, V]]] = {
        val chunkQueue: F[Queue[F, Option[Chunk[CommittableConsumerRecord[F, K, V]]]]] =
          Queue.bounded(settings.maxPrefetchBatches - 1)

        type PartitionRequest =
          (Chunk[CommittableConsumerRecord[F, K, V]], FetchCompletedReason)

        def enqueueStream(
          streamId: Int,
          partitionStreamId: Int,
          partition: TopicPartition,
          partitions: Queue[F, Stream[F, CommittableConsumerRecord[F, K, V]]]
        ): F[Unit] = {
          for {
            chunks <- chunkQueue
            dequeueDone <- Deferred[F, Unit]
            shutdown = F.race(fiber.join.attempt, dequeueDone.get).void
            stopReqs <- Deferred.tryable[F, Unit]
            _ <- partitions.enqueue1 {
              Stream.eval {
                def fetchPartition(deferred: Deferred[F, PartitionRequest]): F[Unit] = {
                  val request =
                    Request.Fetch(partition, streamId, partitionStreamId, deferred.complete)
                  val fetch = requests.enqueue1(request) >> deferred.get
                  F.race(shutdown, fetch).flatMap {
                    case Left(()) =>
                      stopReqs.complete(())

                    case Right((chunk, reason)) =>
                      val enqueueChunk = chunks.enqueue1(Some(chunk)).unlessA(chunk.isEmpty)

                      val completeRevoked =
                        stopReqs.complete(()).whenA(reason.topicPartitionRevoked)

                      enqueueChunk >> completeRevoked
                  }
                }

                Stream
                  .repeatEval {
                    stopReqs.tryGet.flatMap {
                      case None =>
                        Deferred[F, PartitionRequest] >>= fetchPartition

                      case Some(()) =>
                        // Prevent issuing additional requests after partition is
                        // revoked or shutdown happens, in case the stream isn't
                        // interrupted fast enough
                        F.unit
                    }
                  }
                  .interruptWhen(F.race(shutdown, stopReqs.get).void.attempt)
                  .compile
                  .drain
                  .guarantee(F.race(dequeueDone.get, chunks.enqueue1(None)).void)
                  .start
                  .as {
                    chunks.dequeue.unNoneTerminate
                      .flatMap(Stream.chunk)
                      .covary[F]
                      .onFinalize(dequeueDone.complete(()))
                  }
              }.flatten
            }
          } yield ()
        }

        def enqueueStreams(
          streamId: Int,
          partitionStreamIdRef: Ref[F, Int],
          assigned: NonEmptySet[TopicPartition],
          partitions: Queue[F, Stream[F, CommittableConsumerRecord[F, K, V]]]
        ): F[Unit] = assigned.foldLeft(F.unit) {
          case (acc, partition) =>
            acc >> partitionStreamIdRef
              .getAndUpdate(_ + 1)
              .flatMap(enqueueStream(streamId, _, partition, partitions))
        }

        def onRebalance(
          streamId: Int,
          partitionStreamIdRef: Ref[F, Int],
          partitions: Queue[F, Stream[F, CommittableConsumerRecord[F, K, V]]]
        ): OnRebalance[F, K, V] = OnRebalance(
          onAssigned = assigned =>
            NonEmptySet
              .fromSet(assigned)
              .fold(F.unit)(enqueueStreams(streamId, partitionStreamIdRef, _, partitions)),
          onRevoked = _ => F.unit
        )

        def requestAssignment(
          streamId: Int,
          partitionStreamIdRef: Ref[F, Int],
          partitions: Queue[F, Stream[F, CommittableConsumerRecord[F, K, V]]]
        ): F[SortedSet[TopicPartition]] = {
          Deferred[F, Either[Throwable, SortedSet[TopicPartition]]].flatMap { deferred =>
            val request =
              Request.Assignment[F, K, V](
                deferred.complete,
                Some(onRebalance(streamId, partitionStreamIdRef, partitions))
              )
            val assignment = requests.enqueue1(request) >> deferred.get.rethrow
            F.race(fiber.join.attempt, assignment).map {
              case Left(_)         => SortedSet.empty[TopicPartition]
              case Right(assigned) => assigned
            }
          }
        }

        def initialEnqueue(
          streamId: Int,
          partitions: Queue[F, Stream[F, CommittableConsumerRecord[F, K, V]]],
          partitionStreamIdRef: Ref[F, Int]
        ): F[Unit] =
          requestAssignment(streamId, partitionStreamIdRef, partitions).flatMap { assigned =>
            NonEmptySet
              .fromSet(assigned)
              .traverse(
                nonEmpty => enqueueStreams(streamId, partitionStreamIdRef, nonEmpty, partitions)
              )
              .void
          }

        val partitionQueue: F[Queue[F, Stream[F, CommittableConsumerRecord[F, K, V]]]] =
          Queue.unbounded[F, Stream[F, CommittableConsumerRecord[F, K, V]]]

        Stream
          .eval {
            (streamIdRef.getAndUpdate(_ + 1), partitionQueue, Ref[F].of(0)).mapN {
              case (streamId, partitions, partitionStreamIdRef) =>
                initialEnqueue(streamId, partitions, partitionStreamIdRef).as(partitions)
            }.flatten
          }
          .flatMap { _.dequeue.interruptWhen(fiber.join.attempt) }
      }

      override def stream: Stream[F, CommittableConsumerRecord[F, K, V]] =
        partitionedStream.parJoinUnbounded

      private[this] def request[A](
        request: (Either[Throwable, A] => F[Unit]) => Request[F, K, V]
      ): F[A] =
        Deferred[F, Either[Throwable, A]].flatMap { deferred =>
          requests.enqueue1(request(deferred.complete)) >>
            F.race(fiber.join.as(ConsumerShutdownException()), deferred.get.rethrow)
        }.rethrow

      override def assignment: F[SortedSet[TopicPartition]] =
        assignment(Option.empty)

      private def assignment(
        onRebalance: Option[OnRebalance[F, K, V]]
      ): F[SortedSet[TopicPartition]] =
        request { callback =>
          Request.Assignment(
            callback = callback,
            onRebalance = onRebalance
          )
        }

      override def assignmentStream: Stream[F, SortedSet[TopicPartition]] = {
        // NOTE: `initialAssignmentDone` is needed here to guard against the
        // race condition when a rebalance triggers after the listeners are
        // registered but before `assignmentRef` can be updated with initial
        // assignments.
        def onRebalanceWith(
          updateQueue: Queue[F, SortedSet[TopicPartition]],
          assignmentRef: Ref[F, SortedSet[TopicPartition]],
          initialAssignmentDone: F[Unit]
        ): OnRebalance[F, K, V] =
          OnRebalance(
            onAssigned = assigned =>
              initialAssignmentDone >>
                assignmentRef
                  .updateAndGet(_ ++ assigned)
                  .flatMap(updateQueue.enqueue1),
            onRevoked = revoked =>
              initialAssignmentDone >>
                assignmentRef
                  .updateAndGet(_ -- revoked)
                  .flatMap(updateQueue.enqueue1)
          )

        Stream.eval {
          (
            Queue.unbounded[F, SortedSet[TopicPartition]],
            Ref[F].of(SortedSet.empty[TopicPartition]),
            Deferred[F, Unit]
          ).tupled.flatMap {
            case (updateQueue, assignmentRef, initialAssignmentDeferred) =>
              val onRebalance =
                onRebalanceWith(
                  updateQueue = updateQueue,
                  assignmentRef = assignmentRef,
                  initialAssignmentDone = initialAssignmentDeferred.get
                )

              assignment(Some(onRebalance))
                .flatMap { initialAssignment =>
                  assignmentRef.set(initialAssignment) >>
                    updateQueue.enqueue1(initialAssignment) >>
                    initialAssignmentDeferred.complete(())
                }
                .as(updateQueue.dequeue.changes)
          }
        }.flatten
      }

      override def seek(partition: TopicPartition, offset: Long): F[Unit] =
        withConsumer.blocking { _.seek(partition, offset) }

      override def seekToBeginning: F[Unit] =
        seekToBeginning(List.empty[TopicPartition])

      override def seekToBeginning[G[_]](partitions: G[TopicPartition])(
        implicit G: Foldable[G]
      ): F[Unit] =
        withConsumer.blocking { _.seekToBeginning(partitions.asJava) }

      override def seekToEnd: F[Unit] =
        seekToEnd(List.empty[TopicPartition])

      override def seekToEnd[G[_]](
        partitions: G[TopicPartition]
      )(implicit G: Foldable[G]): F[Unit] =
        withConsumer.blocking { _.seekToEnd(partitions.asJava) }

      override def partitionsFor(
        topic: String
      ): F[List[PartitionInfo]] =
        withConsumer.blocking { _.partitionsFor(topic).asScala.toList }

      override def partitionsFor(
        topic: String,
        timeout: FiniteDuration
      ): F[List[PartitionInfo]] =
        withConsumer.blocking { _.partitionsFor(topic, timeout.asJava).asScala.toList }

      override def position(partition: TopicPartition): F[Long] =
        withConsumer.blocking { _.position(partition) }

      override def position(partition: TopicPartition, timeout: FiniteDuration): F[Long] =
        withConsumer.blocking { _.position(partition, timeout.asJava) }

      override def subscribeTo(firstTopic: String, remainingTopics: String*): F[Unit] =
        subscribe(NonEmptyList.of(firstTopic, remainingTopics: _*))

      override def subscribe[G[_]](topics: G[String])(implicit G: Reducible[G]): F[Unit] =
        request { callback =>
          Request.SubscribeTopics(
            topics = topics.toNonEmptyList,
            callback = callback
          )
        }

      override def subscribe(regex: Regex): F[Unit] =
        request { callback =>
          Request.SubscribePattern(
            pattern = regex.pattern,
            callback = callback
          )
        }

      override def unsubscribe: F[Unit] =
        request { callback =>
          Request.Unsubscribe(
            callback = callback
          )
        }

      override def assign(partitions: NonEmptySet[TopicPartition]): F[Unit] =
        request { callback =>
          Request.Assign(
            topicPartitions = partitions,
            callback = callback
          )
        }

      override def assign(topic: String, partitions: NonEmptySet[Int]): F[Unit] =
        assign(partitions.map(new TopicPartition(topic, _)))

      override def assign(topic: String): F[Unit] = {
        for {
          partitions <- partitionsFor(topic)
            .map { partitionInfo =>
              NonEmptySet.fromSet {
                SortedSet(partitionInfo.map(_.partition): _*)
              }
            }
          _ <- partitions.fold(F.unit)(assign(topic, _))
        } yield ()
      }

      override def beginningOffsets(
        partitions: Set[TopicPartition]
      ): F[Map[TopicPartition, Long]] =
        withConsumer.blocking {
          _.beginningOffsets(partitions.asJava)
            .asInstanceOf[util.Map[TopicPartition, Long]]
            .toMap
        }

      override def beginningOffsets(
        partitions: Set[TopicPartition],
        timeout: FiniteDuration
      ): F[Map[TopicPartition, Long]] =
        withConsumer.blocking {
          _.beginningOffsets(partitions.asJava, timeout.asJava)
            .asInstanceOf[util.Map[TopicPartition, Long]]
            .toMap
        }

      override def endOffsets(
        partitions: Set[TopicPartition]
      ): F[Map[TopicPartition, Long]] =
        withConsumer.blocking {
          _.endOffsets(partitions.asJava)
            .asInstanceOf[util.Map[TopicPartition, Long]]
            .toMap
        }

      override def endOffsets(
        partitions: Set[TopicPartition],
        timeout: FiniteDuration
      ): F[Map[TopicPartition, Long]] =
        withConsumer.blocking {
          _.endOffsets(partitions.asJava, timeout.asJava)
            .asInstanceOf[util.Map[TopicPartition, Long]]
            .toMap
        }

      override def metrics: F[Map[MetricName, Metric]] =
        withConsumer.blocking { _.metrics().asScala.toMap }

      override def toString: String =
        "KafkaConsumer$" + id
    }

  def consumerResource[F[_], K, V](
    settings: ConsumerSettings[F, K, V]
  )(
    implicit F: ConcurrentEffect[F],
    context: ContextShift[F],
    timer: Timer[F]
  ): Resource[F, KafkaConsumer[F, K, V]] =
    for {
      keyDeserializer <- Resource.liftF(settings.keyDeserializer)
      valueDeserializer <- Resource.liftF(settings.valueDeserializer)
      id <- Resource.liftF(F.delay(new Object().hashCode))
      implicit0(jitter: Jitter[F]) <- Resource.liftF(Jitter.default[F])
      implicit0(logging: Logging[F]) <- Resource.liftF(Logging.default[F](id))
      requests <- Resource.liftF(Queue.unbounded[F, Request[F, K, V]])
      polls <- Resource.liftF(Queue.bounded[F, Request[F, K, V]](1))
      ref <- Resource.liftF(Ref.of[F, State[F, K, V]](State.empty))
      streamId <- Resource.liftF(Ref.of[F, Int](0))
      withConsumer <- WithConsumer(settings)
      actor = new KafkaConsumerActor(
        settings = settings,
        keyDeserializer = keyDeserializer,
        valueDeserializer = valueDeserializer,
        ref = ref,
        requests = requests,
        withConsumer = withConsumer
      )
      actor <- startConsumerActor(requests, polls, actor)
      polls <- startPollScheduler(polls, settings.pollInterval)
    } yield createKafkaConsumer(requests, settings, actor, polls, streamId, id, withConsumer)
}
