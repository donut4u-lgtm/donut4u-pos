package com.smartpos.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import com.smartpos.data.LabelEntity
import com.smartpos.data.PosDatabase
import com.smartpos.data.Product
import com.smartpos.data.Sale
import com.smartpos.data.SaleItem
import com.smartpos.data.SaleWithItems
import com.smartpos.util.formatTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Optional online mode, using the shop owner's OWN Firebase project.
 *  - SELLER phone: keeps working from its local database and uploads every change to the cloud.
 *  - MONITOR phone: shows the shop live (read-only) from the cloud.
 * All data lives under  users/{uid}/...  and the security rules let only that user read it.
 */
object Cloud {
    const val ROLE_NONE = "none"
    const val ROLE_SELLER = "seller"
    const val ROLE_MONITOR = "monitor"

    private const val APP_NAME = "shop"

    data class State(
        val configured: Boolean = false,
        val signedIn: Boolean = false,
        val email: String = "",
        val role: String = ROLE_NONE,
        val status: String = "",
        val busy: Boolean = false
    )

    val state = MutableStateFlow(State())

    /** Bumped to restart the seller sync (for example after a restore). */
    val syncTick = MutableStateFlow(0)

    // Data shown on the monitor phone
    val monitorProducts = MutableStateFlow<List<Product>>(emptyList())
    val monitorLabels = MutableStateFlow<List<LabelEntity>>(emptyList())
    val monitorSales = MutableStateFlow<List<SaleWithItems>>(emptyList())
    val monitorShopName = MutableStateFlow("")
    val monitorCurrency = MutableStateFlow("₱")
    val monitorStatus = MutableStateFlow("Connecting...")

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    private fun setStatus(text: String) {
        state.update { it.copy(status = text) }
    }

    private fun setBusy(busy: Boolean) {
        state.update { it.copy(busy = busy) }
    }

    // ------------------------------------------------------------------ setup

    /** Creates (or re-creates) the Firebase app from the saved setup code. */
    private fun ensureApp(context: Context): FirebaseApp? {
        val p = prefs(context)
        val projectId = p.getString("projectId", null)
        val apiKey = p.getString("apiKey", null)
        val appId = p.getString("appId", null)
        if (projectId.isNullOrBlank() || apiKey.isNullOrBlank() || appId.isNullOrBlank()) return null
        val appContext = context.applicationContext
        val existing = FirebaseApp.getApps(appContext).firstOrNull { it.name == APP_NAME }
        if (existing != null) {
            val o = existing.options
            if (o.projectId == projectId && o.apiKey == apiKey && o.applicationId == appId) return existing
            existing.delete()
        }
        val options = FirebaseOptions.Builder()
            .setProjectId(projectId)
            .setApiKey(apiKey)
            .setApplicationId(appId)
            .build()
        return FirebaseApp.initializeApp(appContext, options, APP_NAME)
    }

    fun init(context: Context) {
        refresh(context)
    }

    fun refresh(context: Context) {
        val app = try {
            ensureApp(context)
        } catch (e: Exception) {
            null
        }
        val user = app?.let { FirebaseAuth.getInstance(it).currentUser }
        val role = prefs(context).getString("role", ROLE_NONE) ?: ROLE_NONE
        state.update {
            it.copy(
                configured = app != null,
                signedIn = user != null,
                email = user?.email ?: "",
                role = role
            )
        }
    }

    /** Accepts text containing the Project ID, the API key (AIza...) and the App ID. Returns an error or null. */
    fun saveSetupCode(context: Context, text: String): String? {
        val parts = text.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val apiKey = parts.firstOrNull { it.startsWith("AIza") }
        val appId = parts.firstOrNull { Regex("^\\d+:\\d+:android:[0-9a-fA-F]+$").matches(it) }
        val projectId = parts.firstOrNull {
            it != apiKey && it != appId && Regex("^[a-z][a-z0-9-]{3,}$").matches(it)
        }
        if (projectId == null || apiKey == null || appId == null) {
            return "The setup code needs the Project ID, the API key (starts with AIza) and the App ID."
        }
        prefs(context).edit()
            .putString("projectId", projectId)
            .putString("apiKey", apiKey)
            .putString("appId", appId)
            .apply()
        refresh(context)
        return null
    }

    fun setRole(context: Context, role: String) {
        prefs(context).edit().putString("role", role).apply()
        refresh(context)
    }

    suspend fun signIn(context: Context, email: String, password: String, create: Boolean): String? {
        val app = ensureApp(context) ?: return "Paste the setup code first."
        if (email.isBlank() || password.isBlank()) return "Enter your email and password."
        val auth = FirebaseAuth.getInstance(app)
        setBusy(true)
        return try {
            if (create) {
                auth.createUserWithEmailAndPassword(email.trim(), password).await()
            } else {
                auth.signInWithEmailAndPassword(email.trim(), password).await()
            }
            refresh(context)
            null
        } catch (e: Exception) {
            e.message ?: "Could not sign in."
        } finally {
            setBusy(false)
        }
    }

    fun signOut(context: Context) {
        try {
            ensureApp(context)?.let { FirebaseAuth.getInstance(it).signOut() }
        } catch (e: Exception) {
            // ignore
        }
        monitorProducts.value = emptyList()
        monitorLabels.value = emptyList()
        monitorSales.value = emptyList()
        setStatus("")
        refresh(context)
    }

    // ---------------------------------------------------------- doc <-> object

    private fun labelId(name: String): String = "l_" + name.replace('/', '_')

    private fun productHash(p: Product) = "${p.barcode}|${p.name}|${p.price}|${p.stock}|${p.label}"

    private fun productMap(p: Product): Map<String, Any> = hashMapOf(
        "id" to p.id,
        "barcode" to p.barcode,
        "name" to p.name,
        "price" to p.price,
        "stock" to p.stock,
        "label" to p.label,
        "createdAt" to p.createdAt
    )

    private fun saleMap(s: SaleWithItems): Map<String, Any> = hashMapOf(
        "id" to s.sale.id,
        "timestamp" to s.sale.timestamp,
        "total" to s.sale.total,
        "paid" to s.sale.paid,
        "change" to s.sale.change,
        "items" to s.items.map {
            hashMapOf<String, Any>(
                "id" to it.id,
                "productId" to it.productId,
                "name" to it.name,
                "price" to it.price,
                "qty" to it.qty
            )
        }
    )

    private fun productFrom(d: DocumentSnapshot): Product? {
        val id = d.getLong("id") ?: return null
        return Product(
            id = id,
            barcode = d.getString("barcode") ?: "",
            name = d.getString("name") ?: "",
            price = d.getDouble("price") ?: 0.0,
            stock = (d.getLong("stock") ?: 0L).toInt(),
            label = d.getString("label") ?: "Other",
            imagePath = null,
            createdAt = d.getLong("createdAt") ?: 0L
        )
    }

    private fun labelFrom(d: DocumentSnapshot): LabelEntity? {
        val name = d.getString("name") ?: return null
        return LabelEntity(name = name, colorIndex = (d.getLong("colorIndex") ?: 0L).toInt())
    }

    private fun saleFrom(d: DocumentSnapshot): SaleWithItems? {
        val id = d.getLong("id") ?: return null
        val sale = Sale(
            id = id,
            timestamp = d.getLong("timestamp") ?: 0L,
            total = d.getDouble("total") ?: 0.0,
            paid = d.getDouble("paid") ?: 0.0,
            change = d.getDouble("change") ?: 0.0
        )
        val raw = d.get("items") as? List<*> ?: emptyList<Any>()
        val items = raw.mapNotNull { entry ->
            val m = entry as? Map<*, *> ?: return@mapNotNull null
            SaleItem(
                id = (m["id"] as? Number)?.toLong() ?: 0L,
                saleId = id,
                productId = (m["productId"] as? Number)?.toLong() ?: 0L,
                name = m["name"] as? String ?: "",
                price = (m["price"] as? Number)?.toDouble() ?: 0.0,
                qty = (m["qty"] as? Number)?.toInt() ?: 0
            )
        }
        return SaleWithItems(sale = sale, items = items)
    }

    // ------------------------------------------------------ small saved maps

    private fun loadMap(prefs: SharedPreferences, key: String): MutableMap<String, String> {
        val text = prefs.getString(key, null) ?: return HashMap()
        return try {
            val o = JSONObject(text)
            val m = HashMap<String, String>()
            for (k in o.keys()) m[k] = o.getString(k)
            m
        } catch (e: Exception) {
            HashMap()
        }
    }

    private fun saveMap(prefs: SharedPreferences, key: String, map: Map<String, String>) {
        prefs.edit().putString(key, JSONObject(map).toString()).apply()
    }

    private fun commit(fs: FirebaseFirestore, ops: List<(WriteBatch) -> Unit>) {
        if (ops.isEmpty()) return
        for (chunk in ops.chunked(400)) {
            val batch = fs.batch()
            chunk.forEach { it(batch) }
            batch.commit()
                .addOnSuccessListener { setStatus("Synced at ${formatTime(System.currentTimeMillis())}") }
                .addOnFailureListener { e -> setStatus("Upload problem: ${e.message}") }
        }
    }

    // ---------------------------------------------------------------- seller

    /** Runs for as long as this phone is the signed-in SELLER. Cancelled when the role changes. */
    suspend fun runSeller(context: Context) {
        try {
            val app = ensureApp(context) ?: return
            val uid = FirebaseAuth.getInstance(app).currentUser?.uid ?: return
            val fs = FirebaseFirestore.getInstance(app)
            val dao = PosDatabase.get(context).dao()
            val prefs = prefs(context)
            val base = fs.collection("users").document(uid)

            // Safety on a fresh install: never overwrite existing cloud data with an empty phone.
            while (!prefs.getBoolean("init_$uid", false)) {
                val cloudHasData = withTimeoutOrNull(10_000L) {
                    !base.collection("products").limit(1).get(Source.SERVER).await().isEmpty
                }
                if (cloudHasData == null) {
                    setStatus("Waiting for internet to start syncing...")
                    delay(15_000L)
                    continue
                }
                if (cloudHasData && dao.allProducts().isEmpty()) {
                    setStatus("Your cloud already has data. Tap \"Restore from cloud\" first.")
                    return
                }
                prefs.edit().putBoolean("init_$uid", true).apply()
            }

            setStatus("Syncing to your cloud...")

            val settingsPrefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val uploadMeta = {
                val shop = settingsPrefs.getString("shop", null) ?: "donut4u pos"
                val currency = settingsPrefs.getString("currency", null) ?: "₱"
                base.collection("meta").document("shop").set(
                    hashMapOf<String, Any>(
                        "shopName" to shop,
                        "currency" to currency,
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
                Unit
            }
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> uploadMeta() }
            settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
            uploadMeta()

            try {
                coroutineScope {
                    // Products: upload only what changed.
                    launch {
                        dao.observeProducts().collect { list ->
                            val hashes = loadMap(prefs, "ph_$uid")
                            val ops = ArrayList<(WriteBatch) -> Unit>()
                            val ids = HashSet<String>()
                            for (p in list) {
                                val id = p.id.toString()
                                ids += id
                                val h = productHash(p)
                                if (hashes[id] != h) {
                                    hashes[id] = h
                                    val ref = base.collection("products").document(id)
                                    val data = productMap(p)
                                    ops.add { b -> b.set(ref, data) }
                                }
                            }
                            for (id in hashes.keys.toList()) {
                                if (id !in ids) {
                                    hashes.remove(id)
                                    val ref = base.collection("products").document(id)
                                    ops.add { b -> b.delete(ref) }
                                }
                            }
                            if (ops.isNotEmpty()) {
                                commit(fs, ops)
                                saveMap(prefs, "ph_$uid", hashes)
                            }
                        }
                    }
                    // Labels (columns and their colors)
                    launch {
                        dao.observeLabels().collect { list ->
                            val hashes = loadMap(prefs, "lh_$uid")
                            val ops = ArrayList<(WriteBatch) -> Unit>()
                            val ids = HashSet<String>()
                            for (l in list) {
                                val id = labelId(l.name)
                                ids += id
                                val h = "${l.name}|${l.colorIndex}"
                                if (hashes[id] != h) {
                                    hashes[id] = h
                                    val ref = base.collection("labels").document(id)
                                    val data = hashMapOf<String, Any>("name" to l.name, "colorIndex" to l.colorIndex)
                                    ops.add { b -> b.set(ref, data) }
                                }
                            }
                            for (id in hashes.keys.toList()) {
                                if (id !in ids) {
                                    hashes.remove(id)
                                    val ref = base.collection("labels").document(id)
                                    ops.add { b -> b.delete(ref) }
                                }
                            }
                            if (ops.isNotEmpty()) {
                                commit(fs, ops)
                                saveMap(prefs, "lh_$uid", hashes)
                            }
                        }
                    }
                    // Sales: upload new ones, remove voided ones.
                    launch {
                        var previousIds = emptySet<Long>()
                        dao.observeSales().collect { list ->
                            val lastUploaded = prefs.getLong("ls_$uid", 0L)
                            val ops = ArrayList<(WriteBatch) -> Unit>()
                            var maxId = lastUploaded
                            for (s in list.sortedBy { it.sale.id }) {
                                if (s.sale.id > lastUploaded) {
                                    val ref = base.collection("sales").document(s.sale.id.toString())
                                    val data = saleMap(s)
                                    ops.add { b -> b.set(ref, data) }
                                    if (s.sale.id > maxId) maxId = s.sale.id
                                }
                            }
                            val ids = list.map { it.sale.id }.toSet()
                            if (ids.isNotEmpty()) {
                                val minId = ids.min()
                                for (gone in previousIds - ids) {
                                    if (gone >= minId) {
                                        val ref = base.collection("sales").document(gone.toString())
                                        ops.add { b -> b.delete(ref) }
                                    }
                                }
                            }
                            previousIds = ids
                            if (ops.isNotEmpty()) {
                                commit(fs, ops)
                                prefs.edit().putLong("ls_$uid", maxId).apply()
                            }
                        }
                    }
                }
            } finally {
                settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus("Cloud problem: ${e.message}")
        }
    }

    /** Replaces everything on this phone with the data stored in the cloud (for a new or repaired phone). */
    suspend fun restoreFromCloud(context: Context): String? {
        val app = ensureApp(context) ?: return "Not connected."
        val uid = FirebaseAuth.getInstance(app).currentUser?.uid ?: return "Sign in first."
        val base = FirebaseFirestore.getInstance(app).collection("users").document(uid)
        setBusy(true)
        return try {
            val products = base.collection("products").get(Source.SERVER).await().documents.mapNotNull { productFrom(it) }
            val labels = base.collection("labels").get(Source.SERVER).await().documents.mapNotNull { labelFrom(it) }
            val sales = base.collection("sales").get(Source.SERVER).await().documents.mapNotNull { saleFrom(it) }

            val db = PosDatabase.get(context)
            val dao = db.dao()
            db.withTransaction {
                dao.clearSaleItems()
                dao.clearSales()
                dao.clearProducts()
                dao.clearLabels()
                dao.restoreLabels(labels)
                dao.restoreProducts(products)
                dao.restoreSales(sales.map { it.sale })
                dao.restoreSaleItems(sales.flatMap { it.items })
            }

            // Remember that all of this is already in the cloud, so nothing is uploaded twice.
            val prefs = prefs(context)
            val productHashes = HashMap<String, String>()
            products.forEach { productHashes[it.id.toString()] = productHash(it) }
            saveMap(prefs, "ph_$uid", productHashes)
            val labelHashes = HashMap<String, String>()
            labels.forEach { labelHashes[labelId(it.name)] = "${it.name}|${it.colorIndex}" }
            saveMap(prefs, "lh_$uid", labelHashes)
            prefs.edit()
                .putLong("ls_$uid", sales.maxOfOrNull { it.sale.id } ?: 0L)
                .putBoolean("init_$uid", true)
                .apply()
            syncTick.update { it + 1 }
            null
        } catch (e: Exception) {
            "Could not restore: ${e.message}"
        } finally {
            setBusy(false)
        }
    }

    // --------------------------------------------------------------- monitor

    /** Runs for as long as this phone is the signed-in MONITOR. Shows the shop live. */
    suspend fun runMonitor(context: Context) {
        val regs = ArrayList<ListenerRegistration>()
        try {
            val app = ensureApp(context) ?: return
            val uid = FirebaseAuth.getInstance(app).currentUser?.uid ?: return
            val base = FirebaseFirestore.getInstance(app).collection("users").document(uid)
            monitorStatus.value = "Connecting..."

            regs += base.collection("products").addSnapshotListener { snap, err ->
                if (err != null) {
                    monitorStatus.value = "Cloud problem: ${err.message}"
                } else if (snap != null) {
                    monitorProducts.value = snap.documents.mapNotNull { productFrom(it) }
                    monitorStatus.value = if (snap.metadata.isFromCache) {
                        "Showing saved data (no connection)"
                    } else {
                        "Live, updated ${formatTime(System.currentTimeMillis())}"
                    }
                }
            }
            regs += base.collection("labels").addSnapshotListener { snap, err ->
                if (err == null && snap != null) {
                    monitorLabels.value = snap.documents.mapNotNull { labelFrom(it) }
                }
            }
            regs += base.collection("sales")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(300)
                .addSnapshotListener { snap, err ->
                    if (err == null && snap != null) {
                        monitorSales.value = snap.documents.mapNotNull { saleFrom(it) }
                    }
                }
            regs += base.collection("meta").document("shop").addSnapshotListener { snap, err ->
                if (err == null && snap != null && snap.exists()) {
                    monitorShopName.value = snap.getString("shopName") ?: ""
                    monitorCurrency.value = snap.getString("currency") ?: "₱"
                }
            }
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            monitorStatus.value = "Cloud problem: ${e.message}"
        } finally {
            regs.forEach { it.remove() }
        }
    }
}
