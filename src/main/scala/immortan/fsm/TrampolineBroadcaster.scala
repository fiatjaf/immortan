package immortan.fsm

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Features.PrivateRouting
import fr.acinq.eclair.wire._
import immortan._
import immortan.crypto.Tools._
import immortan.crypto.{CanBeShutDown, StateMachine}
import immortan.fsm.TrampolineBroadcaster._
import rx.lang.scala.Observable

import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object TrampolineBroadcaster {
  sealed trait State
  case object RoutingDisabled extends State
  case object RoutingEnabled extends State

  sealed trait BroadcastStatus
  case object RoutingOff extends BroadcastStatus
  case class RoutingOn(params: TrampolineOn) extends BroadcastStatus

  val CMDBroadcast = "cmd-broadcast"

  case class LastBroadcast(
      last: TrampolineStatus,
      info: RemoteNodeInfo,
      maxRoutableRatio: Double
  ) {
    def updated(
        usableChannels: Iterable[ChanAndCommits],
        templateTrampolineOn: TrampolineOn
    ): LastBroadcast = {
      val (peerChannels, otherChannels) =
        usableChannels.partition(_.commits.remoteInfo.nodeId == info.nodeId)

      val canReceiveFromPeer =
        peerChannels.map(_.commits.availableForReceive).sum
      val canSendOut =
        otherChannels.map(_.commits.availableForSend * maxRoutableRatio).sum
      val status =
        templateTrampolineOn.copy(maxMsat = canSendOut min canReceiveFromPeer)

      val last1 = last match {
        case _ if status.minMsat > status.maxMsat => TrampolineUndesired
        case TrampolineUndesired => TrampolineStatusInit(List.empty, status)
        case _ => TrampolineStatusUpdate(List.empty, Map.empty, Some(status))
      }

      copy(last = last1)
    }
  }
}

// Staggered broadcast of routing params to each connected peer when they change (other peers connect/disconnect, balances change, user actions)
class TrampolineBroadcaster(cm: ChannelMaster)
    extends StateMachine[BroadcastStatus, TrampolineBroadcaster.State]
    with ConnectionListener
    with CanBeShutDown { me =>
  implicit val context: ExecutionContextExecutor =
    ExecutionContext fromExecutor Executors.newSingleThreadExecutor

  def initialState = TrampolineBroadcaster.RoutingDisabled

  private val subscription =
    Observable.interval(10.seconds).subscribe(_ => me process CMDBroadcast)
  var broadcasters: Map[PublicKey, LastBroadcast] = Map.empty

  def doBroadcast(msg: Option[TrampolineStatus], info: RemoteNodeInfo): Unit =
    CommsTower.sendMany(msg, info.nodeSpecificPair)
  def process(message: Any): Unit =
    scala.concurrent.Future(me doProcess message)
  override def becomeShutDown(): Unit = subscription.unsubscribe()
  become(RoutingOff, TrampolineBroadcaster.RoutingDisabled)

  override def onOperational(
      worker: CommsTower.Worker,
      theirInit: Init
  ): Unit = {
    val isPrivateRoutingEnabled =
      LNParams.isPeerSupports(theirInit)(feature = PrivateRouting)
    val msg =
      LastBroadcast(TrampolineUndesired, worker.info, maxRoutableRatio = 0.9d)
    if (isPrivateRoutingEnabled) me process msg
  }

  override def onDisconnect(worker: CommsTower.Worker): Unit =
    scala.concurrent.Future(broadcasters -= worker.info.nodeId)

  def doProcess(msg: Any): Unit = (msg, state, data) match {
    case (
          CMDBroadcast,
          TrampolineBroadcaster.RoutingEnabled,
          routingOn: RoutingOn
        ) =>
      // First make a map with updated values, then broadcast differences, then update mapto a new one
      val usableChans = cm.all.values
        .filter(Channel.isOperationalAndOpen)
        .flatMap(Channel.chanAndCommitsOpt)

      val broadcasters1 = for {
        Tuple2(peerNodeId, lastBroadcast) <- broadcasters
        lastBroadcast1 = lastBroadcast.updated(usableChans, routingOn.params)
      } yield peerNodeId -> lastBroadcast1

      for {
        Tuple2(nodeId, lastBroadcast) <- broadcasters
        // To save on traffic we only send out a new status if it differs from an old one
        newBroadcast <- broadcasters1.get(nodeId)
        if newBroadcast.last != lastBroadcast.last
      } doBroadcast(Some(newBroadcast.last), lastBroadcast.info)
      broadcasters = broadcasters1

    case (
          routingOn1: RoutingOn,
          TrampolineBroadcaster.RoutingDisabled |
          TrampolineBroadcaster.RoutingEnabled,
          _
        ) =>
      // User may either enable a previously disabled routing or update params
      become(routingOn1, TrampolineBroadcaster.RoutingEnabled)

    case (RoutingOff, TrampolineBroadcaster.RoutingEnabled, _) =>
      broadcasters =
        for (Tuple2(nodeId, lastBroadcast) <- broadcasters)
          yield nodeId -> lastBroadcast.copy(last = TrampolineUndesired)
      for (lastBroadcast <- broadcasters.values)
        doBroadcast(Some(TrampolineUndesired), lastBroadcast.info)
      become(RoutingOff, TrampolineBroadcaster.RoutingEnabled)

    case (
          lastOn1: LastBroadcast,
          TrampolineBroadcaster.RoutingDisabled |
          TrampolineBroadcaster.RoutingEnabled,
          _
        ) =>
      // A new peer has connected: unconditionally add it to connection map
      broadcasters = broadcasters.updated(lastOn1.info.nodeId, lastOn1)

    case _ =>
  }
}
