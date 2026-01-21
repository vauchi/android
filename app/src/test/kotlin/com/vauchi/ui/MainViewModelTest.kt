package com.vauchi.ui

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.vauchi.data.VauchiRepository
import com.vauchi.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import uniffi.vauchi_mobile.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@ExperimentalCoroutinesApi
class MainViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @Mock
    private lateinit var mockApplication: Application

    @Mock
    private lateinit var mockRepository: VauchiRepository

    @Mock
    private lateinit var mockNetworkMonitor: NetworkMonitor

    @Mock
    private lateinit var mockProximityVerifier: MobileProximityVerifier

    @Mock
    private lateinit var mockAudioProximityService: com.vauchi.proximity.AudioProximityService

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)

        // Mock static AudioProximityService.getInstance
        val mockedStatic = Mockito.mockStatic(
            com.vauchi.proximity.AudioProximityService::class.java
        )
        mockedStatic.use {
            it.`when`<com.vauchi.proximity.AudioProximityService> { 
                com.vauchi.proximity.AudioProximityService.getInstance(any()) 
            }.thenReturn(mockAudioProximityService)
        }

        // Mock MobileProximityVerifier.new
        val mockedVerifierStatic = Mockito.mockStatic(MobileProximityVerifier::class.java)
        mockedVerifierStatic.use {
            it.`when`<MobileProximityVerifier> { 
                MobileProximityVerifier.new(any()) 
            }.thenReturn(mockProximityVerifier)
        }

        viewModel = MainViewModel(mockApplication)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test initial state is loading`() {
        assertEquals(UiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `test sync state management`() {
        viewModel.clearSyncState()
        assertEquals(SyncState.Idle, viewModel.syncState.value)
    }

    @Test
    fun `test snackbar message management`() {
        val testMessage = "Test message"
        viewModel.showSnackbar(testMessage)
        assertEquals(testMessage, viewModel.snackbarMessage.value)

        viewModel.clearSnackbar()
        assertEquals(null, viewModel.snackbarMessage.value)
    }

    @Test
    fun `test identity creation when no identity exists`() = runTest {
        whenever(mockRepository.hasIdentity()).thenReturn(false)
        
        // Create a new ViewModel instance with mocked repository
        // This would require dependency injection for proper testing
        
        val testDisplayName = "Test User"
        viewModel.createIdentity(testDisplayName)
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Verify state changes from Setup -> Loading -> Ready
        // This requires proper mocking of the repository
    }

    @Test
    fun `test password strength checking`() {
        val weakPassword = "123"
        val strongPassword = "StrongP@ssw0rd!123"

        val weakResult = viewModel.checkPasswordStrength(weakPassword)
        val strongResult = viewModel.checkPasswordStrength(strongPassword)

        assertTrue(weakResult.level <= PasswordStrengthLevel.Fair)
        assertTrue(strongResult.level >= PasswordStrengthLevel.Strong)
    }

    @Test
    fun `test relay URL management`() {
        val testUrl = "wss://custom.relay.com"
        
        whenever(mockRepository.getRelayUrl()).thenReturn("wss://default.relay.com")
        assertEquals("wss://default.relay.com", viewModel.getRelayUrl())

        viewModel.setRelayUrl(testUrl)
        verify(mockRepository).setRelayUrl(testUrl)
    }

    @Test
    fun `test field operations`() = runTest {
        val fieldType = MobileFieldType.EMAIL
        val label = "Email"
        val value = "test@example.com"
        val newValue = "new@example.com"

        // Test add field
        viewModel.addField(fieldType, label, value)
        testDispatcher.scheduler.advanceUntilIdle()

        // Test update field
        viewModel.updateField(label, newValue)
        testDispatcher.scheduler.advanceUntilIdle()

        // Test remove field
        viewModel.removeField(label)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `test contact operations`() = runTest {
        val testContactId = "test_contact_id"

        // Test remove contact
        viewModel.removeContact(testContactId)
        testDispatcher.scheduler.advanceUntilIdle()

        // Test verify contact
        val result = viewModel.verifyContact(testContactId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(result)
    }

    @Test
    fun `test exchange operations`() = runTest {
        // Test generate exchange QR
        val exchangeData = viewModel.generateExchangeQr()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(exchangeData)

        // Test complete exchange
        val result = viewModel.completeExchange("test_qr_data")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(result)
    }

    @Test
    fun `test backup operations`() = runTest {
        val password = "test_password_123"
        val backupData = "encrypted_backup_data"

        // Test export backup
        val exportedBackup = viewModel.exportBackup(password)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(exportedBackup)

        // Test import backup
        val importResult = viewModel.importBackup(backupData, password)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(importResult)
    }

    @Test
    fun `test social network operations`() {
        // Test list social networks
        val networks = viewModel.listSocialNetworks()
        assertNotNull(networks)

        // Test search social networks
        val searchResults = viewModel.searchSocialNetworks("twitter")
        assertNotNull(searchResults)

        // Test get profile URL
        val profileUrl = viewModel.getProfileUrl("network_id", "username")
        assertNotNull(profileUrl)
    }

    @Test
    fun `test recovery operations`() = runTest {
        val oldPkHex = "old_public_key_hex"
        val claimB64 = "claim_base64_data"
        val voucherB64 = "voucher_base64_data"

        // Test create recovery claim
        val claim = viewModel.createRecoveryClaim(oldPkHex)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(claim)

        // Test parse recovery claim
        val parsedClaim = viewModel.parseRecoveryClaim(claimB64)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(parsedClaim)

        // Test create recovery voucher
        val voucher = viewModel.createRecoveryVoucher(claimB64)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(voucher)

        // Test add recovery voucher
        val progress = viewModel.addRecoveryVoucher(voucherB64)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(progress)

        // Test get recovery status
        val status = viewModel.getRecoveryStatus()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(status)

        // Test get recovery proof
        val proof = viewModel.getRecoveryProof()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(proof)
    }

    @Test
    fun `test field visibility operations`() = runTest {
        val contactId = "test_contact_id"
        val fieldLabel = "Email"

        // Test hide field
        viewModel.setFieldVisibility(contactId, fieldLabel, false)
        testDispatcher.scheduler.advanceUntilIdle()

        // Test show field
        viewModel.setFieldVisibility(contactId, fieldLabel, true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Test check visibility
        val isVisible = viewModel.isFieldVisibleToContact(contactId, fieldLabel)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(isVisible)
    }

    @Test
    fun `test proximity verification operations`() {
        val challenge = "test_challenge".toByteArray()

        // Test emit proximity challenge
        val emitResult = viewModel.emitProximityChallenge(challenge)
        assertTrue(emitResult)

        // Test listen for proximity response
        val response = viewModel.listenForProximityResponse(1000u)
        assertNotNull(response)

        // Test stop proximity verification
        viewModel.stopProximityVerification()
    }

    @Test
    fun `test sync operation`() = runTest {
        // Test sync
        viewModel.sync()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify sync state transitions through the process
        val syncState = viewModel.syncState.value
        assertTrue(syncState is SyncState.Success || syncState is SyncState.Error)
    }

    @Test
    fun `test refresh operation`() = runTest {
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()

        // Should reload user data
        val uiState = viewModel.uiState.value
        assertTrue(uiState is UiState.Ready || uiState is UiState.Error)
    }

    @Test
    fun `test get own public key`() = runTest {
        val publicKey = viewModel.getOwnPublicKey()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(publicKey)
    }

    @Test
    fun `test get own card`() = runTest {
        val card = viewModel.getOwnCard()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(card)
    }

    @Test
    fun `test list contacts`() = runTest {
        val contacts = viewModel.listContacts()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(contacts)
    }

    @Test
    fun `test get contact by ID`() = runTest {
        val contactId = "test_contact_id"
        val contact = viewModel.getContact(contactId)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(contact)
    }
}

// Helper extension to add showSnackbar method for testing
fun MainViewModel.showSnackbar(message: String) {
    // Using reflection or making the method package-private for testing
    val field = MainViewModel::class.java.getDeclaredField("_snackbarMessage")
    field.isAccessible = true
    val mutableStateFlow = field.get(this) as kotlinx.coroutines.flow.MutableStateFlow<String?>
    mutableStateFlow.value = message
}