package com.kachat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors
import com.kachat.app.util.ChessColor
import com.kachat.app.util.ChessEngine
import com.kachat.app.util.ChessGameEngine
import com.kachat.app.util.ChessGameStatusKind
import com.kachat.app.util.ChessMove
import com.kachat.app.util.ChessPiece
import com.kachat.app.util.ChessPieceType
import com.kachat.app.util.ChessSquare
import com.kachat.app.viewmodels.ChatViewModel
import com.kachat.app.viewmodels.WalletViewModel

/**
 * Full-screen interactive chess board, opened by tapping a chess card in a 1:1 chat
 * ([ChessBubble] in Screens.kt). Board state is entirely derived from the conversation's
 * messages ([ChessGameEngine.summarize]) - re-derived fresh from `chatViewModel.getMessages`
 * (Room-`Flow`-backed) on every recomposition, so a new move arriving while this screen is open
 * updates it automatically, the same way any other message-driven screen in this app stays live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessGameScreen(
    navController: NavController,
    contactId: String,
    gameId: String,
    chatViewModel: ChatViewModel = hiltViewModel(),
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val conversations by chatViewModel.conversations.collectAsState()
    val conversation = conversations.find { it.contact.id == contactId }
    val messages by chatViewModel.getMessages(contactId).collectAsState(initial = emptyList())
    val myAddress by walletViewModel.address.collectAsState()

    val chessSourceMessages = remember(messages) {
        messages.map {
            ChessGameEngine.SimpleChessSourceMessage(
                id = it.id,
                plaintextBody = it.plaintextBody,
                isOutgoing = it.direction == "sent",
                blockTimestamp = it.blockTimestamp
            )
        }
    }
    val summary = remember(chessSourceMessages, myAddress) {
        val address = myAddress
        if (address != null) ChessGameEngine.summarize(gameId, chessSourceMessages, address, contactId) else null
    }
    val myColor = remember(summary, myAddress) {
        val address = myAddress
        if (address != null) summary?.colorFor(address) else null
    }
    val isMyTurn = summary != null && myColor != null && summary.status.kind == ChessGameStatusKind.IN_PROGRESS && summary.board.sideToMove == myColor

    var selectedSquare by remember { mutableStateOf<ChessSquare?>(null) }
    var pendingPromotionMove by remember { mutableStateOf<ChessMove?>(null) }
    var showResignConfirm by remember { mutableStateOf(false) }

    val legalDestinations = remember(selectedSquare, summary) {
        val square = selectedSquare
        val board = summary?.board
        if (square != null && board != null) ChessEngine.legalMoves(square, board).map { it.to } else emptyList()
    }

    fun send(move: ChessMove) {
        chatViewModel.sendChessMove(contactId, gameId, move)
    }

    fun handleTap(square: ChessSquare) {
        val board = summary?.board ?: return
        if (!isMyTurn) return
        val currentSelection = selectedSquare
        if (currentSelection != null) {
            if (legalDestinations.contains(square)) {
                val movingPiece = board.piece(currentSelection)
                val backRank = if (movingPiece?.color == ChessColor.WHITE) 7 else 0
                selectedSquare = null
                if (movingPiece?.type == ChessPieceType.PAWN && square.rank == backRank) {
                    pendingPromotionMove = ChessMove(currentSelection, square, null)
                } else {
                    send(ChessMove(currentSelection, square, null))
                }
                return
            }
            val piece = board.piece(square)
            selectedSquare = if (piece != null && piece.color == myColor) square else null
        } else {
            val piece = board.piece(square)
            if (piece != null && piece.color == myColor) selectedSquare = square
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            conversation?.contact?.alias?.takeIf { it.isNotBlank() } ?: contactId.takeLast(8),
                            color = LocalAppColors.current.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        if (summary != null) {
                            Text(
                                summary.statusText,
                                color = if (summary.status.isGameOver) LocalAppColors.current.textSecondary else KaspaTeal,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LocalAppColors.current.textPrimary)
                    }
                },
                actions = {
                    if (summary != null && summary.status.kind == ChessGameStatusKind.IN_PROGRESS) {
                        TextButton(onClick = { showResignConfirm = true }) {
                            Text("Resign", color = Color(0xFFFF3B30))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalAppColors.current.background)
            )
        },
        containerColor = LocalAppColors.current.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (summary != null) {
                InteractiveChessBoard(
                    board = summary.board,
                    orientation = myColor ?: ChessColor.WHITE,
                    selectedSquare = selectedSquare,
                    legalDestinations = legalDestinations,
                    onSquareTap = ::handleTap
                )
            }
        }
    }

    if (showResignConfirm) {
        AlertDialog(
            onDismissRequest = { showResignConfirm = false },
            title = { Text("Resign this game?") },
            confirmButton = {
                TextButton(onClick = {
                    showResignConfirm = false
                    chatViewModel.resignChessGame(contactId, gameId)
                    navController.popBackStack()
                }) {
                    Text("Resign", color = Color(0xFFFF3B30))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResignConfirm = false }) { Text("Cancel") }
            }
        )
    }

    val pendingMove = pendingPromotionMove
    if (pendingMove != null) {
        AlertDialog(
            onDismissRequest = { pendingPromotionMove = null },
            title = { Text("Promote pawn to:") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for (type in listOf(ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT)) {
                        ChessPieceGlyph(
                            piece = ChessPiece(type, myColor ?: ChessColor.WHITE),
                            fontSize = 36.sp,
                            modifier = Modifier.clickable {
                                pendingPromotionMove = null
                                send(pendingMove.copy(promotion = type))
                            }
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun InteractiveChessBoard(
    board: com.kachat.app.util.ChessBoard,
    orientation: ChessColor,
    selectedSquare: ChessSquare?,
    legalDestinations: List<ChessSquare>,
    onSquareTap: (ChessSquare) -> Unit
) {
    val squareSizeDp = 44.dp
    val ranks = if (orientation == ChessColor.WHITE) (7 downTo 0) else (0..7)
    val files = if (orientation == ChessColor.WHITE) (0..7) else (7 downTo 0)

    Column {
        for (rank in ranks) {
            Row {
                for (file in files) {
                    val square = ChessSquare(file, rank)
                    val isLight = (file + rank) % 2 != 0
                    val isSelected = selectedSquare == square
                    val isDestination = legalDestinations.contains(square)
                    Box(
                        modifier = Modifier
                            .size(squareSizeDp)
                            .background(
                                when {
                                    isSelected -> KaspaTeal.copy(alpha = 0.45f)
                                    isLight -> ChessLightSquareColor
                                    else -> ChessDarkSquareColor
                                }
                            )
                            .clickable { onSquareTap(square) },
                        contentAlignment = Alignment.Center
                    ) {
                        val piece = board.piece(square)
                        if (piece != null) {
                            ChessPieceGlyph(piece, fontSize = 28.sp)
                        }
                        if (isDestination) {
                            Box(
                                modifier = Modifier
                                    .size(squareSizeDp / 3)
                                    .background(KaspaTeal.copy(alpha = 0.6f), CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}
