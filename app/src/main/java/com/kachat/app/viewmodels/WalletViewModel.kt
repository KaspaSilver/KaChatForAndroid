package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletManager: WalletManager,
    private val walletService: WalletService
) : ViewModel() {

    private val _hasWallet = MutableStateFlow(walletManager.hasWallet())
    val hasWallet: StateFlow<Boolean> = _hasWallet

    private val _mnemonic = MutableStateFlow<List<String>?>(null)
    val mnemonic: StateFlow<List<String>?> = _mnemonic

    private val _onMnemonicGenerated = MutableStateFlow<String?>(null)
    val onMnemonicGenerated: StateFlow<String?> = _onMnemonicGenerated

    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address

    private val _accountName = MutableStateFlow<String?>(null)
    val accountName: StateFlow<String?> = _accountName

    private val _accounts = MutableStateFlow(walletManager.getAllAccounts())
    val accounts: StateFlow<List<WalletManager.Account>> = _accounts

    val balance: StateFlow<String> = walletService.balance.map { 
        val kAs = it.toDouble() / 100_000_000.0
        "%.2f KAS".format(kAs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "0.00 KAS")

    val fullBalance: StateFlow<String> = walletService.balance.map {
        val kAs = it.toDouble() / 100_000_000.0
        "%.8f KAS".format(java.util.Locale.US, kAs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "0.00000000 KAS")

    val balanceSompi: StateFlow<Long> = walletService.balance

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    init {
        if (walletManager.hasWallet()) {
            _address.value = walletManager.getAddress()
            _accountName.value = walletManager.getAccountName()
            _accounts.value = walletManager.getAllAccounts()
            refreshBalance()
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            walletService.refreshBalance()
        }
    }

    fun login(address: String? = null) {
        if (address != null) {
            walletManager.setActiveAccount(address)
            _address.value = walletManager.getAddress()
            _accountName.value = walletManager.getAccountName()
            refreshBalance()
        }
        if (walletManager.hasWallet()) {
            _isLoggedIn.value = true
        }
    }

    fun logout() {
        _isLoggedIn.value = false
    }

    fun createWallet(name: String, wordCount: Int = 12) {
        viewModelScope.launch {
            val words = walletManager.createWallet(name, wordCount)
            _mnemonic.value = words
            _onMnemonicGenerated.value = words.joinToString(" ")
            _hasWallet.value = true
            _address.value = walletManager.getAddress()
            _accountName.value = walletManager.getAccountName()
            _accounts.value = walletManager.getAllAccounts()
            refreshBalance()
        }
    }

    fun deleteWallet(address: String) {
        walletManager.deleteAccount(address)
        _accounts.value = walletManager.getAllAccounts()
        _hasWallet.value = walletManager.hasWallet()
        if (!_hasWallet.value) {
            _isLoggedIn.value = false
            _address.value = null
            _accountName.value = null
        }
    }

    fun importWallet(name: String, words: List<String>) {
        viewModelScope.launch {
            walletManager.importWallet(words, name)
            _hasWallet.value = true
            _address.value = walletManager.getAddress()
            _accountName.value = walletManager.getAccountName()
            _accounts.value = walletManager.getAllAccounts()
            refreshBalance()
        }
    }

    fun clearMnemonic() {
        _mnemonic.value = null
        _onMnemonicGenerated.value = null
    }

    fun getActiveMnemonic(): String? = walletManager.getActiveMnemonic()
    fun getPrivateKeyHex(): String = walletManager.getPrivateKeyHex()
}
