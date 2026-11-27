package com.smartpos.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import com.smartpos.data.LabelEntity
import com.smartpos.data.PosDao
import com.smartpos.data.PosDatabase
import com.smartpos.data.Product
import com.smartpos.data.Sale
import com.smartpos.data.SaleItem
import com.smartpos.data.SaleWithItems
import com.smartpos.util.formatTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Optional online mode, using the shop owner's OWN Firebase project.
 *  - SELLER phone: keeps working from its local database, uploads every change to the cloud AND
 *    downloads changes made elsewhere (the website or another seller), so both stay the same.
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

    /** Shop name and currency symbol changed in the cloud (website or another phone). */
    val remoteMeta = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)

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

    /** Same numbering the website uses for a product that has no barcode: P0001, P0002, ... */
    private fun fallbackBarcode(id: Long): String = "P" + id.toString().padStart(4, '0')

    /** A barcode that no other product on this phone uses. */
    private suspend fun freeBarcode(dao: PosDao, id: Long): String {
        var code = fallbackBarcode(id)
        while (dao.findByBarcode(code)?.let { it.id != id } == true) code += "-"
        return code
    }

    private fun productHash(p: Product) = "${p.barcode}|${p.name}|${p.price}|${p.wholesale}|${p.stock}|${p.label}"

    private fun productMap(p: Product): Map<String, Any> = hashMapOf(
        "id" to p.id,
        "barcode" to p.barcode,
        "name" to p.name,
        "price" to p.price,
        "wholesale" to p.wholesale,
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
        "type" to s.sale.type,
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
            barcode = d.getString("barcode")?.takeIf { it.isNotBlank() } ?: fallbackBarcode(id),
            name = d.getString("name") ?: "",
            price = d.getDouble("price") ?: 0.0,
            wholesale = d.getDouble("wholesale") ?: 0.0,
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
            change = d.getDouble("change") ?: 0.0,
            type = d.getString("type") ?: "retail"
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

    // ------------------------------------------------------------ pictures

    /** Same size and format the website uses: a small JPEG (longest side 320 px) as a data URL. */
    private fun encodeImage(path: String): String? {
        return try {
            if (!File(path).exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(path, opts) ?: return null
            val rotation = try {
                when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
            if (rotation != 0f) {
                val m = Matrix().apply { postRotate(rotation) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            val k = minOf(1f, 320f / maxOf(bmp.width, bmp.height))
            val scaled = if (k < 1f) {
                Bitmap.createScaledBitmap(bmp, Math.round(bmp.width * k), Math.round(bmp.height * k), true)
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /** Saves a picture that came from the cloud as a file on this phone. Returns its path. */
    private fun saveCloudImage(context: Context, id: Long, dataUrl: String): String? {
        return try {
            val comma = dataUrl.indexOf(',')
            if (!dataUrl.startsWith("data:image") || comma < 0) return null
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            val dir = File(context.applicationContext.filesDir, "product_images").apply { mkdirs() }
            val file = File(dir, "c_${id}_${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun imageKey(dataUrl: String) = "${dataUrl.length}:${dataUrl.hashCode()}"

    private fun commit(fs: FirebaseFirestore, ops: List<(WriteBatch) -> Unit>) {
        if (ops.isEmpty()) return
        for (chunk in ops.chunked(100)) {
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
            val regs = ArrayList<ListenerRegistration>()
            val lock = Mutex()

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
            val metaRef = base.collection("meta").document("shop")
            // If the cloud already has a shop name, the cloud wins (it is delivered below). Otherwise send ours.
            val cloudMeta = try {
                withTimeoutOrNull(8_000L) { metaRef.get(Source.SERVER).await() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // offline right now: keep going, the live listener below will catch up
            }
            if (cloudMeta != null && !cloudMeta.exists()) uploadMeta()

            // One-time: the saved fingerprints now include the wholesale price. Convert them so
            // products that did not change are not uploaded again.
            if (!prefs.getBoolean("hv2_$uid", false)) {
                val old = loadMap(prefs, "ph_$uid")
                for (p in dao.allProducts()) {
                    val oldHash = "${p.barcode}|${p.name}|${p.price}|${p.stock}|${p.label}"
                    if (old[p.id.toString()] == oldHash) old[p.id.toString()] = productHash(p)
                }
                saveMap(prefs, "ph_$uid", old)
                prefs.edit().putBoolean("hv2_$uid", true).apply()
            }

            try {
                coroutineScope {
                    // Products: upload only what changed.
                    launch {
                        dao.observeProducts().collect { _ -> lock.withLock {
                            // Always read the phone's database again here (never trust an older copy of the list).
                            val list = dao.allProducts()
                            val hashes = loadMap(prefs, "ph_$uid")
                            val ops = ArrayList<(WriteBatch) -> Unit>()
                            val ids = HashSet<String>()
                            val sent = loadMap(prefs, "pi_$uid")
                            var sentChanged = false
                            for (p in list) {
                                val id = p.id.toString()
                                ids += id
                                val h = productHash(p)
                                val path = p.imagePath
                                val imageDue = path != null && sent[id] != path
                                val ref = base.collection("products").document(id)
                                if (hashes[id] != h) {
                                    hashes[id] = h
                                    val data = HashMap<String, Any>(productMap(p))
                                    if (path != null && imageDue) {
                                        val img = withContext(Dispatchers.IO) { encodeImage(path) }
                                        sent[id] = path
                                        sentChanged = true
                                        if (img != null) data["image"] = img
                                    }
                                    ops.add { b -> b.set(ref, data, SetOptions.merge()) }
                                } else if (path != null && imageDue) {
                                    // Only the picture is new: send just the picture (never old stock numbers).
                                    val img = withContext(Dispatchers.IO) { encodeImage(path) }
                                    sent[id] = path
                                    sentChanged = true
                                    if (img != null) {
                                        ops.add { b -> b.set(ref, hashMapOf<String, Any>("image" to img), SetOptions.merge()) }
                                    }
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
                                if (sentChanged) saveMap(prefs, "pi_$uid", sent)
                            }
                        } }
                    }
                    // Labels (columns and their colors)
                    launch {
                        dao.observeLabels().collect { _ -> lock.withLock {
                            val list = dao.allLabels()
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
                        } }
                    }
                    // Sales: upload new ones, remove voided ones.
                    launch {
                        var previousIds = emptySet<Long>()
                        dao.observeSales().collect { _ -> lock.withLock {
                            val list = dao.allSales()
                            val lastUploaded = prefs.getLong("ls_$uid", 0L)
                            val ops = ArrayList<(WriteBatch) -> Unit>()
                            var maxId = lastUploaded
                            for (s in list.sortedBy { it.sale.id }) {
                                if (s.sale.id > lastUploaded) {
                                    val ref = base.collection("sales").document(s.sale.id.toString())
                                    val data = saleMap(s)
                                    ops.add { b -> b.set(ref, data, SetOptions.merge()) }
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
                        } }
                    }

                    // DOWNLOAD: apply changes made on the website (or another phone) to this phone.
                    val scope = this
                    fun handle(what: String, snap: com.google.firebase.firestore.QuerySnapshot?, err: Exception?,
                               block: suspend (List<DocumentChange>) -> Unit) {
                        if (err != null) {
                            setStatus("Cloud problem ($what): ${err.message}")
                            return
                        }
                        val changes = snap?.documentChanges?.toList() ?: return
                        if (changes.isEmpty()) return
                        scope.launch {
                            lock.withLock {
                                try {
                                    block(changes)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    setStatus("Cloud problem ($what): ${e.message}")
                                }
                            }
                        }
                    }
                    regs += base.collection("products").addSnapshotListener { snap, err ->
                        handle("products", snap, err) { applyRemoteProducts(context, dao, prefs, uid, it) }
                    }
                    regs += base.collection("labels").addSnapshotListener { snap, err ->
                        handle("labels", snap, err) { applyRemoteLabels(dao, prefs, uid, it) }
                    }
                    regs += base.collection("sales").addSnapshotListener { snap, err ->
                        handle("sales", snap, err) { applyRemoteSales(dao, it) }
                    }
                    regs += metaRef.addSnapshotListener { snap, err ->
                        if (err == null && snap != null && snap.exists() && !snap.metadata.hasPendingWrites()) {
                            val shop = snap.getString("shopName") ?: ""
                            val symbol = snap.getString("currency") ?: ""
                            if (shop.isNotBlank()) remoteMeta.tryEmit(shop to symbol.ifBlank { "₱" })
                        }
                    }
                }
            } finally {
                regs.forEach { it.remove() }
                settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus("Cloud problem: ${e.message}")
        }
    }

    // ------------------------------------------------- download (cloud -> phone)

    private suspend fun applyRemoteProducts(
        context: Context, dao: PosDao, prefs: SharedPreferences, uid: String, changes: List<DocumentChange>
    ) {
        val hashes = loadMap(prefs, "ph_$uid")
        val sent = loadMap(prefs, "pi_$uid")   // picture file already in the cloud, per product
        val seen = loadMap(prefs, "ih_$uid")   // cloud picture already on this phone, per product
        var dirty = false
        for (c in changes) {
            val d = c.document
            if (d.metadata.hasPendingWrites()) continue // this phone's own upload, not news
            if (c.type == DocumentChange.Type.REMOVED) {
                val id = d.getLong("id") ?: d.id.toLongOrNull()
                if (id != null) {
                    dao.getById(id)?.imagePath?.let { File(it).delete() }
                    hashes.remove(id.toString())
                    sent.remove(id.toString())
                    seen.remove(id.toString())
                    dao.deleteProduct(id)
                    dirty = true
                }
                continue
            }
            val remote = productFrom(d) ?: continue
            val key = remote.id.toString()
            val local = dao.getById(remote.id)
            val img = d.getString("image")
            val imgKey = if (img != null && img.startsWith("data:image")) imageKey(img) else null

            val fieldsChanged = local == null || productHash(local) != productHash(remote)
            val imageChanged = imgKey != null && seen[key] != imgKey
            if (!fieldsChanged && !imageChanged) continue

            // Never overwrite something changed on this phone that has not been uploaded yet.
            val fieldsWaiting = local != null && hashes[key] != productHash(local)
            val imageWaiting = local != null && local.imagePath != null && sent[key] != local.imagePath
            val applyFields = fieldsChanged && !fieldsWaiting
            val applyImage = imageChanged && !imageWaiting
            if (!applyFields && !applyImage) continue

            var incoming = remote
            var needsFix = d.getString("barcode").isNullOrBlank()
            if (applyFields) {
                // Two products must never share a barcode: give this one its own number instead of hiding it.
                val sameBarcode = dao.findByBarcode(incoming.barcode)
                if (sameBarcode != null && sameBarcode.id != incoming.id) {
                    incoming = incoming.copy(barcode = freeBarcode(dao, incoming.id))
                    needsFix = true
                }
            }
            var imagePath = local?.imagePath
            if (applyImage && img != null && imgKey != null) {
                val saved = withContext(Dispatchers.IO) { saveCloudImage(context, remote.id, img) }
                if (saved != null) {
                    local?.imagePath?.let { old -> if (old != saved) File(old).delete() }
                    imagePath = saved
                    sent[key] = saved
                }
                seen[key] = imgKey
            }
            val target = if (applyFields || local == null) incoming else local
            if (applyFields) {
                // A product that got a new barcode is sent back to the cloud so the website shows it too.
                if (needsFix) hashes.remove(key) else hashes[key] = productHash(incoming)
            }
            dao.restoreProducts(listOf(target.copy(imagePath = imagePath)))
            dirty = true
        }
        if (dirty) {
            saveMap(prefs, "ph_$uid", hashes)
            saveMap(prefs, "pi_$uid", sent)
            saveMap(prefs, "ih_$uid", seen)
        }
    }

    private suspend fun applyRemoteLabels(
        dao: PosDao, prefs: SharedPreferences, uid: String, changes: List<DocumentChange>
    ) {
        val hashes = loadMap(prefs, "lh_$uid")
        var dirty = false
        for (c in changes) {
            val d = c.document
            if (d.metadata.hasPendingWrites()) continue
            if (c.type == DocumentChange.Type.REMOVED) {
                val name = d.getString("name") ?: d.id.removePrefix("l_")
                if (dao.allProducts().none { it.label == name }) {
                    hashes.remove(labelId(name))
                    dao.deleteLabel(name)
                    dirty = true
                }
                continue
            }
            val remote = labelFrom(d) ?: continue
            val key = labelId(remote.name)
            val local = dao.allLabels().firstOrNull { it.name == remote.name }
            if (local == null) {
                dao.insertLabel(remote)
            } else {
                if (hashes[key] != "${local.name}|${local.colorIndex}") continue // local change waiting to upload
                if (local.colorIndex != remote.colorIndex) dao.setLabelColor(remote.name, remote.colorIndex)
            }
            hashes[key] = "${remote.name}|${remote.colorIndex}"
            dirty = true
        }
        if (dirty) saveMap(prefs, "lh_$uid", hashes)
    }

    private suspend fun applyRemoteSales(dao: PosDao, changes: List<DocumentChange>) {
        var existing: Map<Long, SaleWithItems>? = null
        for (c in changes) {
            val d = c.document
            if (d.metadata.hasPendingWrites()) continue
            if (c.type == DocumentChange.Type.REMOVED) {
                val id = d.getLong("id") ?: d.id.toLongOrNull()
                if (id != null) dao.deleteSale(id)
                continue
            }
            val remote = saleFrom(d) ?: continue
            if (existing == null) existing = dao.allSales().associateBy { it.sale.id }
            val local = existing[remote.sale.id]
            if (local != null &&
                local.sale.total == remote.sale.total &&
                local.sale.timestamp == remote.sale.timestamp &&
                local.sale.type == remote.sale.type &&
                local.items.size == remote.items.size
            ) continue
            // Remove the old copy of this sale (its items go with it), then add the new one.
            dao.deleteSale(remote.sale.id)
            dao.restoreSales(listOf(remote.sale))
            dao.restoreSaleItems(remote.items.map { it.copy(id = 0L) })
        }
    }


    /** Replaces everything on this phone with the data stored in the cloud (for a new or repaired phone). */
    suspend fun restoreFromCloud(context: Context): String? {
        val app = ensureApp(context) ?: return "Not connected."
        val uid = FirebaseAuth.getInstance(app).currentUser?.uid ?: return "Sign in first."
        val base = FirebaseFirestore.getInstance(app).collection("users").document(uid)
        setBusy(true)
        return try {
            val productDocs = base.collection("products").get(Source.SERVER).await().documents
            val sentMap = HashMap<String, String>()
            val seenMap = HashMap<String, String>()
            val needFix = HashSet<String>()
            val rawProducts = withContext(Dispatchers.IO) {
                productDocs.mapNotNull { d ->
                    val p = productFrom(d) ?: return@mapNotNull null
                    if (d.getString("barcode").isNullOrBlank()) needFix += p.id.toString()
                    val img = d.getString("image")
                    val path = if (img != null && img.startsWith("data:image")) saveCloudImage(context, p.id, img) else null
                    if (path != null && img != null) {
                        sentMap[p.id.toString()] = path
                        seenMap[p.id.toString()] = imageKey(img)
                    }
                    p.copy(imagePath = path)
                }
            }
            // Two products with the same barcode would erase each other: keep both.
            val usedCodes = HashSet<String>()
            val products = rawProducts.map { p ->
                if (usedCodes.add(p.barcode)) {
                    p
                } else {
                    needFix += p.id.toString()
                    p.copy(barcode = fallbackBarcode(p.id) + "-d")
                }
            }
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
                dao.restoreSaleItems(sales.flatMap { it.items }.map { it.copy(id = 0L) })
            }

            // Remember that all of this is already in the cloud, so nothing is uploaded twice.
            val prefs = prefs(context)
            val productHashes = HashMap<String, String>()
            products.forEach { if (it.id.toString() !in needFix) productHashes[it.id.toString()] = productHash(it) }
            saveMap(prefs, "ph_$uid", productHashes)
            saveMap(prefs, "pi_$uid", sentMap)
            saveMap(prefs, "ih_$uid", seenMap)
            val labelHashes = HashMap<String, String>()
            labels.forEach { labelHashes[labelId(it.name)] = "${it.name}|${it.colorIndex}" }
            saveMap(prefs, "lh_$uid", labelHashes)
            prefs.edit()
                .putLong("ls_$uid", sales.maxOfOrNull { it.sale.id } ?: 0L)
                .putBoolean("init_$uid", true)
                .putBoolean("hv2_$uid", true)
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
