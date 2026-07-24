package com.kachat.app.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * "Play Chess" 1:1 chat feature - four JSON envelopes embedded directly in the plaintext message
 * body, same convention as [MessageReply]/[VoiceMessage]/[ImageMessage] (no wire-protocol
 * change). All four share one `gameId` (a UUID minted by the inviter) - [ChessGameSummary]
 * derives the current board by scanning a conversation's messages for that `gameId` and replaying
 * them through [ChessEngine], the same way [MessageReply.replyToId] is resolved client-side
 * against the in-memory message list rather than a database relationship.
 */
/** `@SerializedName` pins the wire value to lowercase "white"/"black" - Gson's default enum
 *  serialization uses the constant name verbatim ("WHITE"/"BLACK"), but iOS's Swift `Codable`
 *  enum encodes its raw String value, which defaults to the lowercase case name. Without this,
 *  every chess_invite sent from one platform silently failed to parse on the other (caught by
 *  parseOrNull's broad catch, falling through to plain-text rendering) - since the invite is the
 *  mandatory first message of every game, that broke the whole game, not just the color choice. */
enum class ChessInviteColor {
    @SerializedName("white") WHITE,
    @SerializedName("black") BLACK
}

data class ChessInviteContent(
    val type: String = "chess_invite",
    val gameId: String,
    val inviterColor: ChessInviteColor
)

data class ChessResponseContent(
    val type: String = "chess_response",
    val gameId: String,
    val accepted: Boolean
)

data class ChessMoveContent(
    val type: String = "chess_move",
    val gameId: String,
    val from: String,
    val to: String,
    val promotion: String?
)

data class ChessResignContent(
    val type: String = "chess_resign",
    val gameId: String
)

/** Any one of the four chess envelope shapes, parsed generically. */
sealed class ChessEnvelope {
    abstract val gameId: String

    data class Invite(val content: ChessInviteContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
    data class Response(val content: ChessResponseContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
    data class Move(val content: ChessMoveContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
    data class Resign(val content: ChessResignContent) : ChessEnvelope() {
        override val gameId: String get() = content.gameId
    }
}

/** Same conventions as [MessageReply]: a plain JSON envelope embedded directly as message
 *  content, with a `{`-prefix + size guard before attempting a full parse, since this runs on
 *  every visible message row alongside reply/image/voice parsing. */
object ChessMessage {
    private val gson = Gson()

    private data class TypeOnly(val type: String)

    fun encode(content: ChessInviteContent): String = gson.toJson(content)
    fun encode(content: ChessResponseContent): String = gson.toJson(content)
    fun encode(content: ChessMoveContent): String = gson.toJson(content)
    fun encode(content: ChessResignContent): String = gson.toJson(content)

    fun parseOrNull(text: String?): ChessEnvelope? {
        if (text.isNullOrBlank() || text.length > 100_000 || text.trimStart().firstOrNull() != '{') return null
        return try {
            val typeOnly = gson.fromJson(text, TypeOnly::class.java) ?: return null
            when (typeOnly.type) {
                "chess_invite" -> gson.fromJson(text, ChessInviteContent::class.java)?.let { ChessEnvelope.Invite(it) }
                "chess_response" -> gson.fromJson(text, ChessResponseContent::class.java)?.let { ChessEnvelope.Response(it) }
                "chess_move" -> gson.fromJson(text, ChessMoveContent::class.java)?.let { ChessEnvelope.Move(it) }
                "chess_resign" -> gson.fromJson(text, ChessResignContent::class.java)?.let { ChessEnvelope.Resign(it) }
                else -> null
            }
        } catch (e: Exception) {
            // Same broad catch as MessageReply.parseOrNull - Gson's reflection deserialization
            // doesn't honor Kotlin non-null defaults for absent JSON keys.
            null
        }
    }
}

/** Game-over-ness and a human-readable status line for a derived board state. */
enum class ChessGameStatusKind { PENDING_RESPONSE, DECLINED, IN_PROGRESS, CHECKMATE, STALEMATE, RESIGNED }

data class ChessGameStatus(
    val kind: ChessGameStatusKind,
    /** Only meaningful for CHECKMATE (winner) / RESIGNED (loser). */
    val color: ChessColor? = null
) {
    val isGameOver: Boolean get() = kind != ChessGameStatusKind.PENDING_RESPONSE && kind != ChessGameStatusKind.IN_PROGRESS
}

data class ChessGameSummary(
    val gameId: String,
    val status: ChessGameStatus,
    val board: ChessBoard,
    val whiteAddress: String,
    val blackAddress: String,
    /** id of the most recent chess-related message for this game - used to key Compose
     *  recomposition (`key(...)`) without needing full board equality checks in call sites. */
    val lastMessageId: String,
    /** Which color the local device is playing, if it's a participant - lets `statusText` say
     *  "Your turn"/"Their turn" instead of absolute White/Black, which a casual player has to
     *  stop and translate back to "wait, am I white or black in this one?" every time. */
    val viewerColor: ChessColor? = null
) {
    fun colorFor(address: String): ChessColor? = when (address) {
        whiteAddress -> ChessColor.WHITE
        blackAddress -> ChessColor.BLACK
        else -> null
    }

    val statusText: String
        get() = when (status.kind) {
            ChessGameStatusKind.PENDING_RESPONSE -> "Waiting for response"
            ChessGameStatusKind.DECLINED -> "Game declined"
            ChessGameStatusKind.IN_PROGRESS -> {
                if (viewerColor == null) {
                    if (board.sideToMove == ChessColor.WHITE) "White to move" else "Black to move"
                } else if (board.sideToMove == viewerColor) "Your turn" else "Their turn"
            }
            ChessGameStatusKind.CHECKMATE -> {
                if (viewerColor == null) {
                    "Checkmate - ${if (status.color == ChessColor.WHITE) "White" else "Black"} wins"
                } else if (status.color == viewerColor) "Checkmate - You win!" else "Checkmate - You lost"
            }
            ChessGameStatusKind.STALEMATE -> "Stalemate - draw"
            ChessGameStatusKind.RESIGNED -> {
                if (viewerColor == null) {
                    "${if (status.color == ChessColor.WHITE) "White" else "Black"} resigned"
                } else if (status.color == viewerColor) "You resigned" else "They resigned"
            }
        }
}

/**
 * Derives a game's current state from a conversation's already-loaded messages - never persisted
 * on its own. Ported 1:1 from iOS's `ChessGameService.summarize`.
 */
object ChessGameEngine {
    /** A minimal view over whichever message type calls this (1:1 `MessageEntity`) - avoids this
     *  pure logic depending on Room/DB types directly. */
    interface ChessSourceMessage {
        val id: String
        val plaintextBody: String?
        val isOutgoing: Boolean
        val blockTimestamp: Long
    }

    /** Plain adapter for wrapping a `MessageEntity` (`direction == "sent"` -> `isOutgoing`) or any
     *  other message shape without needing this file to depend on Room/DB types directly. */
    data class SimpleChessSourceMessage(
        override val id: String,
        override val plaintextBody: String?,
        override val isOutgoing: Boolean,
        override val blockTimestamp: Long
    ) : ChessSourceMessage

    fun summarize(gameId: String, messages: List<ChessSourceMessage>, myAddress: String, contactAddress: String): ChessGameSummary? {
        var invite: ChessInviteContent? = null
        var inviterAddress: String? = null
        var response: ChessResponseContent? = null
        var board = ChessEngine.initialBoard()
        var resignerAddress: String? = null
        var lastMessageId: String? = null

        for (message in messages.sortedBy { it.blockTimestamp }) {
            val replyUnwrapped = MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
            val envelope = ChessMessage.parseOrNull(replyUnwrapped) ?: continue
            if (envelope.gameId != gameId) continue
            lastMessageId = message.id
            val senderAddress = if (message.isOutgoing) myAddress else contactAddress

            when (envelope) {
                is ChessEnvelope.Invite -> {
                    invite = envelope.content
                    inviterAddress = senderAddress
                }
                is ChessEnvelope.Response -> {
                    response = envelope.content
                }
                is ChessEnvelope.Move -> {
                    val from = ChessSquare.fromAlgebraic(envelope.content.from) ?: continue
                    val to = ChessSquare.fromAlgebraic(envelope.content.to) ?: continue
                    val promotion = ChessPieceType.fromPromotionLetter(envelope.content.promotion)
                    val move = ChessEngine.normalizingPromotion(ChessMove(from, to, promotion), board)
                    if (!ChessEngine.isLegal(move, board)) continue
                    board = ChessEngine.apply(move, board)
                }
                is ChessEnvelope.Resign -> {
                    resignerAddress = senderAddress
                }
            }
        }

        val nonNullInvite = invite ?: return null
        val nonNullInviterAddress = inviterAddress ?: return null
        val otherAddress = if (nonNullInviterAddress == myAddress) contactAddress else myAddress
        val whiteAddress = if (nonNullInvite.inviterColor == ChessInviteColor.WHITE) nonNullInviterAddress else otherAddress
        val blackAddress = if (nonNullInvite.inviterColor == ChessInviteColor.WHITE) otherAddress else nonNullInviterAddress

        val status = when {
            resignerAddress != null -> {
                val loser = if (resignerAddress == whiteAddress) ChessColor.WHITE else ChessColor.BLACK
                ChessGameStatus(ChessGameStatusKind.RESIGNED, loser)
            }
            response != null && !response.accepted -> ChessGameStatus(ChessGameStatusKind.DECLINED)
            response == null -> ChessGameStatus(ChessGameStatusKind.PENDING_RESPONSE)
            ChessEngine.isCheckmate(board) -> ChessGameStatus(ChessGameStatusKind.CHECKMATE, board.sideToMove.opposite)
            ChessEngine.isStalemate(board) -> ChessGameStatus(ChessGameStatusKind.STALEMATE)
            else -> ChessGameStatus(ChessGameStatusKind.IN_PROGRESS)
        }

        val viewerColor = when (myAddress) {
            whiteAddress -> ChessColor.WHITE
            blackAddress -> ChessColor.BLACK
            else -> null
        }

        return ChessGameSummary(
            gameId = gameId,
            status = status,
            board = board,
            whiteAddress = whiteAddress,
            blackAddress = blackAddress,
            lastMessageId = lastMessageId ?: "",
            viewerColor = viewerColor
        )
    }

    /** True if `message` is any chess envelope and no *later* message in `messages` shares its
     *  `gameId` - i.e. this is the current/latest state for that game, which is the only one that
     *  should render as the live status card (earlier moves render as a compact log line
     *  instead, so a long game doesn't repeat a full board on every message). */
    fun isLatestChessMessage(message: ChessSourceMessage, messages: List<ChessSourceMessage>): Boolean {
        val envelope = ChessMessage.parseOrNull(
            MessageReply.parseOrNull(message.plaintextBody)?.text ?: message.plaintextBody
        ) ?: return false
        return messages.none { other ->
            other.blockTimestamp > message.blockTimestamp &&
                ChessMessage.parseOrNull(MessageReply.parseOrNull(other.plaintextBody)?.text ?: other.plaintextBody)?.gameId == envelope.gameId
        }
    }
}
