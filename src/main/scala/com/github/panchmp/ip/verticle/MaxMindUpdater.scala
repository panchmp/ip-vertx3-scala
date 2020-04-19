package com.github.panchmp.ip.verticle

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

import com.github.panchmp.ip.utils.CloseableUtils.using
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpHeaders
import io.vertx.ext.web.impl.Utils
import io.vertx.lang.scala.ScalaVerticle
import io.vertx.scala.core.eventbus.Message
import io.vertx.scala.ext.web.client.{HttpResponse, WebClient, WebClientOptions}
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.utils.IOUtils
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.{Failure, Success}

class MaxMindUpdater extends ScalaVerticle {
  private val log = LoggerFactory.getLogger(classOf[MaxMindUpdater])

  override def start(): Unit = {
    vertx.eventBus().localConsumer[String]("maxmind/update/local", (_: Message[String]) => {
      updateByLocal()
    })

    scheduleLocalUpdate

    vertx.eventBus().localConsumer[String]("maxmind/update/remote", (_: Message[String]) => {
      downloadMaxmind()
    })

    scheduleRemoteUpdate()
  }

  override def stop(): Unit = {
    val localMap = vertx.sharedData().getLocalMap[String, String]("maxmind.db")
    Option(localMap.get("path")).map((path: String) =>
      deleteFile(path)
    )
  }

  def scheduleLocalUpdate = {
    vertx.eventBus().publish("maxmind/update/local", None)
  }

  def scheduleRemoteUpdate(): Unit = {
    vertx.eventBus().publish("maxmind/update/remote", None)
  }

  private def downloadMaxmind(): Unit = {
    Option(config.getString("maxmind.db.remote.url")).fold {
      log.warn("Config option [maxmind.db.remote.url] not specified")
    }((remoteUrl: String) => {
      val apiKey = config.getString("maxmind.license.key", "")
      val url = remoteUrl.replace("LICENSE_KEY", apiKey)

      val updateInterval = config.getLong("maxmind.db.update.interval", TimeUnit.HOURS.toMillis(4))
      val updateRepeatInterval = config.getLong("maxmind.db.update.repeat.interval", TimeUnit.MINUTES.toMillis(5))

      val localMap = vertx.sharedData().getLocalMap[String, String]("maxmind.db")
      val lastModified: String = localMap.get("lastModified")

      val webClientOptions = WebClientOptions()
        .setTryUseCompression(true)

      Option(config.getString("maxmind.db.remote.useragent")).map(value => {
        webClientOptions.setUserAgent(value)
      })

      val webClient = WebClient.create(vertx, webClientOptions)
      try {
        webClient.getAbs(url)
          .putHeader(HttpHeaders.IF_MODIFIED_SINCE.toString, lastModified)
          .sendFuture().map((httpResponse: HttpResponse[Buffer]) => {
          log.info("Download {}", url)
          if (HttpResponseStatus.NOT_MODIFIED.code() == httpResponse.statusCode) {
            log.info("{} not modified", url)
          } else if (HttpResponseStatus.OK.code() == httpResponse.statusCode) {
            httpResponse.bodyAsBuffer().map((buffer: Buffer) => {
              val bytes = extractDBFile(buffer)
              val lastModifiedNew = httpResponse.getHeader(HttpHeaders.LAST_MODIFIED.toString).orNull
              saveFile(bytes, Utils.parseRFC1123DateTime(lastModifiedNew).toString, lastModifiedNew)
            })
          } else {
            val code = httpResponse.statusCode
            val message = httpResponse.bodyAsString().getOrElse("")
            log.warn(s"Can't download $url. Response code $code: $message")
          }
        }).onComplete {
          case Success(_) =>
            vertx.setTimer(updateInterval, _ => scheduleRemoteUpdate())
          case Failure(ex) =>
            log.warn("Can't update MaxMind DB", ex)
            vertx.setTimer(updateRepeatInterval, _ => scheduleRemoteUpdate())
        }
      } finally
        webClient.close()
    })
  }

  private def updateByLocal(): Unit = {
    val maxMindDbPath = config.getString("maxmind.db.local.path")
    if (maxMindDbPath == null || maxMindDbPath.isEmpty) {
      log.warn("Config option [maxmind.db.local.path] not specified")
    } else {
      vertx.fileSystem().readFileFuture(maxMindDbPath).flatMap((archiveBuffer: Buffer) => {
        val dbBuffer = extractDBFile(archiveBuffer)
        saveFile(dbBuffer, "local")
      }).onComplete({
        case Success(_) => log.info("Successfully update MaxMind DB from {}", maxMindDbPath);
        case Failure(ex) => log.error("Can't update MaxMind DB from " + maxMindDbPath, ex)
      })
    }

  }

  private def extractDBFile(archiveBuffer: Buffer): Buffer = {
    using(new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(archiveBuffer.getBytes)))) {
      archiveInputStream => {
        var entry = archiveInputStream.getNextEntry
        while (entry != null) {
          if (archiveInputStream.canReadEntryData(entry)) {
            log.debug("Find file {}", entry.getName)
            if (entry.getName.endsWith(".mmdb")) {
              val bytes = IOUtils.toByteArray(archiveInputStream)
              return Buffer.buffer(bytes)
            }
          } else {
            log.warn("Can't read entry {}", entry.getName)
          }
          entry = archiveInputStream.getNextEntry
        }
        throw new IllegalStateException("Cant find file *.mmmd")
      }
    }
  }

  private def saveFile(dbBuffer: Buffer, filePrefix: String, lastModified: String = null): Future[Unit] = {
    val fileSystem = vertx.fileSystem()
    fileSystem.createTempFileFuture(filePrefix + "_", ".mmmd").flatMap((dbPath: String) => {
      fileSystem.writeFileFuture(dbPath, dbBuffer).map(_ => {
        log.info(s"Local file: [$dbPath], lastModified: [$lastModified]")

        val localMap = vertx.sharedData().getLocalMap[String, String]("maxmind.db")

        val oldFilePath = localMap.put("path", dbPath)
        if (!dbPath.equals(oldFilePath))
          deleteFile(oldFilePath)

        Option(lastModified)
          .fold(localMap.remove("lastModified"))(localMap.put("lastModified", _))
      })
    })
  }

  private def deleteFile(path: String) = {
    Option(path).map(f => {
      if (vertx.fileSystem().existsBlocking(f)) {
        vertx.fileSystem().deleteBlocking(path)
      }
    })
  }
}