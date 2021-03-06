package fr.acinq.eclair.blockchain.electrum

import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import fr.acinq.bitcoin.{Block, BlockHeader, ByteVector32}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool._
import immortan.LNParams
import org.json4s.JsonAST.{JObject, JString}
import org.json4s.native.JsonMethods

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Try, Random}

class ElectrumClientPool(
    blockCount: AtomicLong,
    chainHash: ByteVector32,
    useOnion: Boolean = false,
    customAddress: Option[ElectrumServerAddress] = None
)(implicit
    ac: castor.Context
) extends CastorStateMachineActorWithState[Any] { self =>
  val serverAddresses: Set[ElectrumServerAddress] = customAddress match {
    case Some(address) => Set(address)
    case None => {
      val addresses = loadFromChainHash(chainHash)
      if (useOnion) addresses
      else
        addresses.filterNot(address =>
          address.address.getHostName().endsWith(".onion")
        )
    }
  }
  val addresses =
    scala.collection.mutable.Map.empty[ElectrumClient, InetSocketAddress]
  val statusListeners =
    scala.collection.mutable.HashSet.empty[castor.SimpleActor[Any]]

  def initialState: State = Disconnected()
  def stay = state

  case class Disconnected()
      extends State({
        case (ElectrumClient.ElectrumReady(source, height, tip, _), _)
            if addresses.contains(source) => {
          source.subscribeToHeaders(self.asInstanceOf[castor.SimpleActor[Any]])
          handleHeader(source, height, tip, None)
        }

        case (ElectrumClient.ElectrumDisconnected(source), _) => {
          val t = new java.util.Timer()
          val task = new java.util.TimerTask { def run() = self.connect() }
          t.schedule(task, 5000L)
          addresses -= source
          stay
        }

        case _ => stay
      })

  case class Connected(
      master: ElectrumClient,
      tips: Map[ElectrumClient, TipAndHeader]
  ) extends State({
        case (
              ElectrumClient.ElectrumReady(source, height, tip, _),
              d: Connected
            ) if addresses.contains(source) => {
          source.subscribeToHeaders(self.asInstanceOf[castor.SimpleActor[Any]])
          handleHeader(source, height, tip, Some(d))
        }

        case (
              ElectrumClient.HeaderSubscriptionResponse(source, height, tip),
              d: Connected
            ) if addresses.contains(source) => {
          handleHeader(source, height, tip, Some(d))
        }

        case (ElectrumClient.ElectrumDisconnected(source), d: Connected)
            if addresses.contains(source) => {
          val address = addresses(source)
          val tips = d.tips - source

          val t = new java.util.Timer()
          val task = new java.util.TimerTask { def run() = self.connect() }
          t.schedule(task, 5000L)
          addresses -= source

          if (tips.isEmpty) {
            System.err.println(
              s"[info] lost connection to $address, no active connections left"
            )
            statusListeners.foreach(
              // we send the original disconnect source here but it doesn't matter, pool listeners will ignore it since there is only one pool
              _.send(ElectrumClient.ElectrumDisconnected(source))
            )
            EventStream
              .publish(ElectrumClient.ElectrumDisconnected)
            Disconnected() // no more connections
          } else if (d.master != source) {
            System.err.println(
              s"[debug] lost connection to $address, we still have our master server"
            )
            Connected(
              master = master,
              tips = tips
            ) // we don't care, this wasn't our master
          } else {
            System.err
              .println(s"[info] lost connection to our master server $address")
            // we choose next best candidate as master
            val tips = d.tips - source
            val (bestClient, bestTip) = tips.toSeq.maxBy(_._2._1)
            handleHeader(
              bestClient,
              bestTip._1,
              bestTip._2,
              Some(d.copy(tips = tips))
            )
          }
        }

        case _ => stay
      }) {
    def blockHeight: Int = tips.get(master).map(_._1).getOrElse(0)
  }

  def initConnect(): Unit = {
    try {
      val connections =
        Math.min(LNParams.maxChainConnectionsCount, serverAddresses.size)
      (0 until connections).foreach(_ => self.connect())
    } catch {
      case _: java.lang.NoClassDefFoundError =>
    }
  }

  def connect(): Unit = {
    pickAddress(serverAddresses, addresses.values.toSet)
      .foreach { esa =>
        val client = new ElectrumClient(self, esa)
        client.addStatusListener(self.asInstanceOf[castor.SimpleActor[Any]])
        addresses += (client -> esa.address)
      }
  }

  def addStatusListener(listener: castor.SimpleActor[Any]): Unit = {
    state match {
      case _: Disconnected =>
        statusListeners += listener
      case Connected(master, tips) if addresses.contains(master) => {
        statusListeners += listener
        val (height, tip) = tips(master)
        listener.send(
          ElectrumClient.ElectrumReady(
            master, // this field is ignored by ElectrumClientPool listeners (since there is only one pool)
            height,
            tip,
            addresses(master)
          )
        )
      }
    }
  }

  def subscribeToHeaders(
      listener: castor.SimpleActor[Any]
  ): Unit = {
    state match {
      case Connected(master, _) => master.subscribeToHeaders(listener)
      case _ =>
        Future.failed(
          new Exception(
            "subscription must be started only after the client is connected"
          )
        )
    }
  }

  def subscribeToScriptHash(
      scriptHash: ByteVector32,
      listener: castor.SimpleActor[Any]
  ): Unit = {
    state match {
      case Connected(master, _) =>
        master.subscribeToScriptHash(scriptHash, listener)
      case _ =>
        Future.failed(
          new Exception(
            "subscription must be started only after the client is connected"
          )
        )
    }
  }

  def request(r: ElectrumClient.Request): Future[ElectrumClient.Response] =
    state match {
      case Connected(master, _) => master.request(r)
      case _ =>
        Future.failed(
          new Exception(
            "request must be called only after the client is connected"
          )
        )
    }

  private def handleHeader(
      connection: ElectrumClient,
      height: Int,
      tip: BlockHeader,
      data: Option[Connected] = None
  ): State = {
    val remoteAddress = addresses(connection)
    // we update our block count even if it doesn't come from our current master
    updateBlockCount(height)
    data match {
      case None => {
        // as soon as we have a connection to an electrum server, we select it as master
        System.err.println(s"[info] selecting master $remoteAddress at $height")
        statusListeners.foreach(
          _.send(
            ElectrumClient.ElectrumReady(
              connection,
              height,
              tip,
              remoteAddress
            )
          )
        )
        EventStream.publish(
          ElectrumClient.ElectrumReady(connection, height, tip, remoteAddress)
        )
        Connected(
          connection,
          Map(connection -> (height, tip))
        )
      }
      case Some(d) if connection != d.master && height > d.blockHeight + 2 => {
        // we only switch to a new master if there is a significant difference with our current master, because
        // we don't want to switch to a new master every time a new block arrives (some servers will be notified before others)
        // we check that the current connection is not our master because on regtest when you generate several blocks at once
        // (and maybe on testnet in some pathological cases where there's a block every second) it may seen like our master
        // skipped a block and is suddenly at height + 2
        System.err.println(
          s"[info] switching to master $remoteAddress at $tip"
        )
        // we've switched to a new master, treat this as a disconnection/reconnection
        // so users (wallet, watcher, ...) will reset their subscriptions
        statusListeners.foreach(_.send(ElectrumClient.ElectrumDisconnected))
        EventStream.publish(ElectrumClient.ElectrumDisconnected)
        statusListeners.foreach(
          _.send(
            ElectrumClient.ElectrumReady(d.master, height, tip, remoteAddress)
          )
        )
        EventStream.publish(
          ElectrumClient.ElectrumReady(d.master, height, tip, remoteAddress)
        )
        Connected(
          master = connection,
          tips = d.tips + (connection -> (height, tip))
        )
      }
      case Some(d) => d.copy(tips = d.tips + (connection -> (height, tip)))
    }
  }

  private def updateBlockCount(blockCount: Long): Unit = {
    // when synchronizing we don't want to advertise previous blocks
    if (this.blockCount.get() < blockCount) {
      System.err.println(s"[debug] current blockchain height=$blockCount")
      EventStream.publish(CurrentBlockCount(blockCount))
      this.blockCount.set(blockCount)
    }
  }
}

object ElectrumClientPool {
  case class ElectrumServerAddress(address: InetSocketAddress, ssl: SSL)
  def loadFromChainHash(chainHash: ByteVector32): Set[ElectrumServerAddress] =
    readServerAddresses(
      classOf[
        ElectrumServerAddress
      ] getResourceAsStream ("/electrum/servers_" +
        (chainHash match {
          case Block.LivenetGenesisBlock.hash => "mainnet.json"
          case Block.SignetGenesisBlock.hash  => "signet.json"
          case Block.TestnetGenesisBlock.hash => "testnet.json"
          case Block.RegtestGenesisBlock.hash => "regtest.json"
          case _                              => throw new RuntimeException
        }))
    )

  def readServerAddresses(stream: InputStream): Set[ElectrumServerAddress] =
    try {
      val JObject(values) = JsonMethods.parse(stream)

      for ((name, fields) <- values.toSet) yield {
        val port = Try((fields \ "s").asInstanceOf[JString].s.toInt).toOption
          .getOrElse(0)
        val address = InetSocketAddress.createUnresolved(name, port)
        ElectrumServerAddress(address, SSL.LOOSE)
      }

    } finally {
      stream.close
    }

  def pickAddress(
      serverAddresses: Set[ElectrumServerAddress],
      usedAddresses: Set[InetSocketAddress] = Set.empty
  ): Option[ElectrumServerAddress] =
    Random
      .shuffle(
        serverAddresses
          .filterNot(serverAddress =>
            usedAddresses contains serverAddress.address
          )
          .toSeq
      )
      .headOption

  type TipAndHeader = (Int, BlockHeader)
}
