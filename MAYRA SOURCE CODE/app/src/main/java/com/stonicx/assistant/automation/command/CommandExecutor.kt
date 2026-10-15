package com.stonicx.assistant.automation.command

import android.util.Log
import com.stonicx.assistant.automation.phone.PhoneControlManager
import com.stonicx.assistant.automation.whatsapp.WhatsAppAssistant
import com.stonicx.assistant.automation.youtube.YouTubeAutomationManager
import com.stonicx.assistant.data.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandExecutor(
    private val phoneManager: PhoneControlManager,
    private val youtubeManager: YouTubeAutomationManager,
    private val whatsappAssistant: WhatsAppAssistant,
    private val memoryManager: MemoryManager
) {
    companion object {
        private const val TAG = "CommandExecutor"
    }

    suspend fun execute(command: StonicXCommand): String = withContext(Dispatchers.Main) {
        Log.i(TAG, "Executing command: $command")
        when (command) {
            // YouTube
            is StonicXCommand.PlayYouTube -> {
                val resolvedSong = memoryManager.resolveSongQuery(command.query)
                memoryManager.trackSongPlay(resolvedSong)
                youtubeManager.playVideo(resolvedSong)
            }
            is StonicXCommand.SearchYouTube -> youtubeManager.searchYouTube(command.query)
            is StonicXCommand.PauseMedia -> youtubeManager.pausePlayback()
            is StonicXCommand.ResumeMedia -> youtubeManager.resumePlayback()
            is StonicXCommand.NextMedia -> youtubeManager.nextMedia()
            is StonicXCommand.PreviousMedia -> youtubeManager.previousMedia()
            is StonicXCommand.SubscribeChannel -> youtubeManager.subscribeToChannel()
            is StonicXCommand.ToggleFullscreen -> youtubeManager.toggleFullscreen()

            // Phone Controls
            is StonicXCommand.Flash -> phoneManager.setFlashlight(command.enabled)
            is StonicXCommand.Brightness -> {
                if (command.isRelative) {
                    phoneManager.adjustBrightness(command.delta > 0)
                } else {
                    phoneManager.setBrightnessPercent(command.level)
                }
            }
            is StonicXCommand.Volume -> {
                if (command.isRelative) {
                    phoneManager.adjustVolume(command.delta > 0)
                } else {
                    phoneManager.setVolumePercent(command.level)
                }
            }
            is StonicXCommand.OpenApp -> phoneManager.openApp(command.packageName)
            is StonicXCommand.OpenWebUrls -> phoneManager.openMultipleUrls(command.urls)
            is StonicXCommand.PhoneCall -> phoneManager.makePhoneCall(command.contactOrNumber)
            is StonicXCommand.SearchWeb -> phoneManager.searchWeb(command.query)

            // WhatsApp
            is StonicXCommand.WhatsAppMessage -> whatsappAssistant.sendMessageDirectly(command.contact, command.message)
            is StonicXCommand.WhatsAppCall -> whatsappAssistant.makeCall(command.contact, command.video)
            is StonicXCommand.WhatsAppDeleteMessage -> whatsappAssistant.deleteLastMessage(command.contact, command.forEveryone)
            is StonicXCommand.ConfirmAction -> {
                if (command.confirmed) {
                    whatsappAssistant.confirmAndSend()
                } else {
                    whatsappAssistant.cancelDraft()
                }
            }
            is StonicXCommand.DisambiguationChoice -> whatsappAssistant.handleDisambiguationChoice(command.choice)

            // Memory
            is StonicXCommand.Remember -> memoryManager.rememberFact(command.text)
            is StonicXCommand.Forget -> memoryManager.forgetFact(command.query)
            is StonicXCommand.WhatDoYouRemember -> memoryManager.getWhatDoYouRemember()
            is StonicXCommand.RecallBirthday -> memoryManager.getBirthdayResponse()
            is StonicXCommand.RecallFavoriteSong -> memoryManager.getFavoriteSongResponse()
            is StonicXCommand.RecallRoutine -> memoryManager.getRoutineResponse()
            is StonicXCommand.GoToBackground -> "Going to background."
        }
    }
}
