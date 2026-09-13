package com.winlator.cmod.store

import com.winlator.cmod.store.proto.CloudConfigStore.CCloudConfigStore_Download_Request
import com.winlator.cmod.store.proto.CloudConfigStore.CCloudConfigStore_Download_Response
import `in`.dragonbra.javasteam.base.PacketClientMsgProtobuf
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse
import `in`.dragonbra.javasteam.types.AsyncJobSingle

/**
 * Unified-messages stub for Steam's `CloudConfigStore` service, which is where the user's
 * library collections live.
 *
 * JavaSteam 1.8.0.1-18 generates no stub for this service, and its generic
 * [SteamUnifiedMessages.sendMessage] cannot receive a reply on its own: incoming
 * `ServiceMethodResponse` packets are routed by service name through a handler map that only
 * [SteamUnifiedMessages.createService] populates. Without a registered service whose
 * [serviceName] is "CloudConfigStore" the download reply is dropped and the job times out.
 *
 * So this registers that name and forwards the Download reply to the right protobuf type,
 * the same way the jar's own generated services do.
 */
class CloudConfigStoreService(
    unifiedMessages: SteamUnifiedMessages,
) : UnifiedService(unifiedMessages) {

    override val serviceName: String = "CloudConfigStore"

    fun download(
        request: CCloudConfigStore_Download_Request,
    ): AsyncJobSingle<ServiceMethodResponse<CCloudConfigStore_Download_Response.Builder>> =
        unifiedMessages!!.sendMessage(
            CCloudConfigStore_Download_Response.Builder::class.java,
            "CloudConfigStore.Download#1",
            request,
        )

    override fun handleResponseMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) {
        when (methodName) {
            "Download" -> postResponseMsg<CCloudConfigStore_Download_Response.Builder>(
                CCloudConfigStore_Download_Response::class.java,
                packetMsg,
            )
        }
    }

    override fun handleNotificationMsg(methodName: String, packetMsg: PacketClientMsgProtobuf) {
        // CloudConfigStore sends a NotifyChange notification we do not consume.
    }
}
