package xt.server

import java.net.InetSocketAddress
import java.util.concurrent.Executors

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory

import xt._
import xt.Config
import xt.middleware.App

class Server(app: App) {
  def start {
    val bootstrap = new ServerBootstrap(new NioServerSocketChannelFactory(
      Executors.newCachedThreadPool, Executors.newCachedThreadPool))
    bootstrap.setPipelineFactory(new ChannelPipelineFactory(app))
    bootstrap.setOption("reuseAddress", true)
    bootstrap.setOption("child.tcpNoDelay", true)
    bootstrap.setOption("child.keepAlive",  true)
    bootstrap.bind(new InetSocketAddress(Config.httpPort))

    Log.info("Xitrum started on port " + Config.httpPort)
  }
}