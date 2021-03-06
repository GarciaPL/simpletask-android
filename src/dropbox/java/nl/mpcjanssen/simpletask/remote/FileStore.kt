package nl.mpcjanssen.simpletask.remote


import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import com.dropbox.client2.DropboxAPI
import com.dropbox.client2.RESTUtility
import com.dropbox.client2.android.AndroidAuthSession
import com.dropbox.client2.exception.DropboxException
import com.dropbox.client2.exception.DropboxIOException
import com.dropbox.client2.exception.DropboxServerException
import com.dropbox.client2.exception.DropboxUnlinkedException
import com.dropbox.client2.jsonextract.JsonExtractionException
import com.dropbox.client2.jsonextract.JsonThing
import com.dropbox.client2.session.AppKeyPair
import nl.mpcjanssen.simpletask.Constants
import nl.mpcjanssen.simpletask.Logger
import nl.mpcjanssen.simpletask.R
import nl.mpcjanssen.simpletask.TodoApplication
import nl.mpcjanssen.simpletask.util.*
import java.io.*
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList


/**
 * FileStore implementation backed by Dropbox
 */
class FileStore(private val mApp: TodoApplication, private val mFileChangedListerer: FileStoreInterface.FileChangeListener) : FileStoreInterface {
    private val log: Logger
    private val mPrefs: SharedPreferences?
    // In the class declaration section:
    private var mDBApi: DropboxAPI<AndroidAuthSession>? = null
    private var latestCursor: String? = null
    private var pollingTask: Thread? = null
    internal var continuePolling = true
    internal var onOnline: Thread? = null
    override var isLoading = false
    private var mOnline: Boolean = false
    private var fileOperationsQueue: Handler? = null

    init {
        log = Logger
        mPrefs = mApp.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        // Set up the message queue
        val t = Thread(Runnable {
            Looper.prepare()
            fileOperationsQueue = Handler()
            Looper.loop()
        })
        t.start()
        mOnline = isOnline
        setMDBApi(mApp)
    }

    private fun loadContentsFromCache(): String {
        if (mPrefs == null) {
            log.warn(TAG, "Couldn't load cache from preferences, mPrefs == null")
            return ""
        }
        return mPrefs.getString(LOCAL_CONTENTS, "")
    }

    fun queueRunnable(description: String, r: Runnable) {
        log.info(TAG, "Handler: Queue " + description)
        while (fileOperationsQueue == null) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }

        }
        fileOperationsQueue!!.post(r)
    }

    override fun changesPending(): Boolean {
        if (mPrefs == null) {
            log.error(TAG, "Couldn't read pending changes state, mPrefs == null")
            return false
        }
        return mPrefs.getBoolean(LOCAL_CHANGES_PENDING, false)
    }

    private fun saveToCache(fileName: String, rev: String?, contents: String) {
        log.info(TAG, "Storing file in cache rev: $rev of file: $fileName")
        if (mPrefs == null) {
            return
        }
        val edit = mPrefs.edit()
        edit.putString(LOCAL_NAME, fileName)
        edit.putString(LOCAL_CONTENTS, contents)
        edit.putString(LOCAL_REVISION, rev)
        edit.commit()
    }

    private fun setChangesPending(pending: Boolean) {
        if (pending) {
            showToastLong(mApp, R.string.write_failed)
        }
        if (mPrefs == null) {
            log.error(TAG, "Couldn't save pending changes, mPrefs == null")
            return
        }
        if (pending) {
            log.info(TAG, "Changes are pending")
        }
        val edit = mPrefs.edit()
        edit.putBoolean(LOCAL_CHANGES_PENDING, pending).commit()
        mApp.localBroadCastManager.sendBroadcast(Intent(Constants.BROADCAST_UPDATE_PENDING_CHANGES))
    }

    private val isOnline: Boolean
        get() {
            val cm = mApp.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val netInfo = cm.activeNetworkInfo
            return netInfo != null && netInfo.isConnected
        }

    private val localTodoRev: String?
        get() {
            if (mPrefs == null) {
                return ""
            }
            return mPrefs.getString(LOCAL_REVISION, null)
        }

    private fun setMDBApi(app: TodoApplication) {
        val app_secret: String
        var app_key: String
        if (mDBApi == null) {
            // Full access or folder access?
            if (app.fullDropBoxAccess) {
                app_secret = mApp.getString(R.string.dropbox_consumer_secret)
                app_key = mApp.getString(R.string.dropbox_consumer_key)
            } else {
                app_secret = mApp.getString(R.string.dropbox_folder_consumer_secret)
                app_key = mApp.getString(R.string.dropbox_folder_consumer_key)
            }
            app_key = app_key.replaceFirst("^db-".toRegex(), "")
            // And later in some initialization function:
            val appKeys = AppKeyPair(app_key, app_secret)
            val savedAuth = mPrefs!!.getString(OAUTH2_TOKEN, null)
            val session = AndroidAuthSession(appKeys, savedAuth)
            mDBApi = DropboxAPI(session)
        }
    }


    private fun startLongPoll(polledFile: String, backoffSeconds: Int) {
        pollingTask = Thread(Runnable {
            val longpoll_timeout = 480
            var newBackoffSeconds = 0
            var start_time = System.currentTimeMillis()
            if (!continuePolling) return@Runnable
            try {
                //log.info(TAG, "Long polling");

                val params = ArrayList<String>()
                latestCursor?.let {
                    params.add("cursor")
                    params.add(it)
                }
                params.add("timeout")
                params.add("$longpoll_timeout")
                if (backoffSeconds != 0) {
                    log.info(TAG, "Backing off for $backoffSeconds seconds")
                    try {
                        Thread.sleep((backoffSeconds * 1000).toLong())
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }

                }
                if (!continuePolling) return@Runnable
                val response = RESTUtility.request(RESTUtility.RequestMethod.GET, "api-notify.dropbox.com", "longpoll_delta", 1, params.toArray<String>(arrayOfNulls<String>(params.size)), mDBApi!!.session)
                log.info(TAG, "Longpoll response: " + response.toString())
                val result = JsonThing(response)
                val resultMap = result.expectMap()
                val changes = resultMap.get("changes").expectBoolean()
                val backoffThing = resultMap.getOrNull("backoff")

                if (backoffThing != null) {
                    newBackoffSeconds = backoffThing.expectInt32()
                }
                log.info(TAG, "Longpoll ended, changes $changes backoff $newBackoffSeconds")
                if (changes) {
                    val delta = mDBApi!!.delta(latestCursor)
                    latestCursor = delta.cursor
                    for (entry in delta.entries) {
                        if (entry.lcPath.equals(polledFile, ignoreCase = true)) {
                            if (entry.metadata == null || entry.metadata.rev == null) {
                                throw DropboxException("Metadata (or rev) in entry is null " + entry)
                            }
                            if (entry.metadata.rev == localTodoRev) {
                                log.info(TAG, "Remote file " + polledFile + " changed, rev: " + entry.metadata.rev + " same as local rev, not reloading")
                            } else {
                                log.info(TAG, "Remote file " + polledFile + " changed, rev: " + entry.metadata.rev + " reloading")
                                mFileChangedListerer.fileChanged(null)
                            }
                        }
                    }
                }
            } catch (e: DropboxUnlinkedException) {
                log.info(TAG, "Dropbox unlinked, no more polling")
                continuePolling = false
            } catch (e: DropboxIOException) {
                if (e is SocketTimeoutException) {
                    //log.info(TAG, "Longpoll timed out, restarting");
                    if (!isOnline) {
                        log.info(TAG, "Device was not online, stopping polling")
                        continuePolling = false
                    }
                    if (System.currentTimeMillis() - start_time < longpoll_timeout * 1000) {
                        log.info(TAG, "Longpoll timed out to quick, backing off for 60 seconds")
                        newBackoffSeconds = 60
                    }
                } else {
                    log.info(TAG, "Longpoll IO exception, restarting backing of {} seconds" + 30, e)
                    newBackoffSeconds = 30
                }
            } catch (e: JsonExtractionException) {
                log.info(TAG, "Longpoll Json exception, restarting backing of {} seconds" + 30, e)
                newBackoffSeconds = 30
            } catch (e: DropboxException) {
                log.info(TAG, "Longpoll Dropbox exception, restarting backing of {} seconds" + 30, e)
                newBackoffSeconds = 30
            }

            startLongPoll(polledFile, newBackoffSeconds)
        })
        pollingTask!!.start()
    }

    // Required to complete auth, sets the access token on the session
    override val isAuthenticated: Boolean
        get() {
            if (mDBApi == null) {
                return false
            }
            if (mDBApi!!.session.isLinked) {
                return true
            }
            if (mDBApi!!.session.authenticationSuccessful()) {
                try {
                    mDBApi!!.session.finishAuthentication()
                    val accessToken = mDBApi!!.session.oAuth2AccessToken
                    mPrefs!!.edit().putString(OAUTH2_TOKEN, accessToken).commit()
                    return true
                } catch (e: IllegalStateException) {
                    log.info(TAG, "Error authenticating", e)
                }

            }
            return false
        }


    @Synchronized @Throws(IOException::class)
    override fun loadTasksFromFile(path: String, backup: BackupInterface?, eol: String): List<String> {

        // If we load a file and changes are pending, we do not want to overwrite
        // our local changes, instead we upload local and handle any conflicts
        // on the dropbox side.

        log.info(TAG, "Loading file fom dropnbox: " + path)
        isLoading = true
        if (!isAuthenticated) {
            isLoading = false
            throw IOException("Not authenticated")
        }
        val readFile = ArrayList<String>()
        if (changesPending()) {
            log.info(TAG, "Not loading, changes pending")
            showToastLong(mApp, "Saving pending changes")
            isLoading = false
            saveTasksToFile(path, tasksFromCache(), backup, eol)
            startWatching(path)
            return tasksFromCache()
        } else {
            try {
                val openFileStream: DropboxAPI.DropboxInputStream
                val fileInfo: DropboxAPI.DropboxFileInfo
                try {
                    openFileStream = mDBApi!!.getFileStream(path, null)
                    fileInfo = openFileStream.fileInfo
                    log.info(TAG, "The file's rev is: " + fileInfo.metadata.rev)
                } catch (e: DropboxServerException) {
                    log.debug(TAG, "Dropbox server exception", e)
                    if (e.error == DropboxServerException._404_NOT_FOUND) {
                        log.info(TAG, "File not found, creating file instead")
                        val toStore = "".toByteArray()
                        val `in` = ByteArrayInputStream(toStore)
                        mDBApi!!.putFile(path, `in`,
                                toStore.size.toLong(), null, null)
                        openFileStream = mDBApi!!.getFileStream(path, null)
                        fileInfo = openFileStream.fileInfo
                    } else {
                        throw e
                    }
                }

                val reader = BufferedReader(InputStreamReader(openFileStream, "UTF-8"))

                reader.forEachLine { line ->
                    readFile.add(line)
                }
                openFileStream.close()
                val contents = join(readFile, "\n")
                backup?.backup(path, contents)
                saveToCache(fileInfo.metadata.fileName(), fileInfo.metadata.rev, contents)
                startWatching(path)
            } catch (e: DropboxException) {
                // Couldn't download file use cached version
                e.printStackTrace()
                readFile.clear()
                readFile.addAll(tasksFromCache())
                showToastLong(mApp, "Dropbox error, loading from cache")
            } catch (e: IOException) {
                isLoading = false
                throw IOException(e)
            }

        }
        isLoading = false
        return readFile
    }

    private fun tasksFromCache(): List<String> {
        val result = CopyOnWriteArrayList<String>()
        val contents = loadContentsFromCache()
        for (line in contents.split("(\r\n|\r|\n)".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            result.add(line)
        }
        return result
    }

    override fun startLogin(caller: Activity, i: Int) {
        // MyActivity below should be your activity class name
        mDBApi = null
        setMDBApi(mApp)
        mDBApi!!.session.startOAuth2Authentication(caller)
    }


    private fun startWatching(path: String) {
        queueRunnable("startWatching", Runnable {
            continuePolling = true
            if (pollingTask == null) {
                log.info(TAG, "Initializing slow polling thread")
                try {
                    log.info(TAG, "Finding latest cursor")
                    val params = ArrayList<String>()
                    params.add("include_media_info")
                    params.add("false")
                    val response = RESTUtility.request(RESTUtility.RequestMethod.POST, "api.dropbox.com", "delta/latest_cursor", 1, params.toArray<String>(arrayOfNulls<String>(params.size)), mDBApi!!.session)
                    log.info(TAG, "Longpoll latestcursor response: " + response.toString())
                    val result = JsonThing(response)
                    val resultMap = result.expectMap()
                    latestCursor = resultMap.get("cursor").expectString()
                } catch (e: DropboxException) {
                    e.printStackTrace()
                    log.error(TAG, "Error reading polling cursor" + e)
                } catch (e: JsonExtractionException) {
                    e.printStackTrace()
                    log.error(TAG, "Error reading polling cursor" + e)
                }

                log.info(TAG, "Starting slow polling")
                startLongPoll(path, 0)
            }
        })
    }


    private fun stopWatching() {
        queueRunnable("stopWatching", Runnable {
            continuePolling = false
            pollingTask = null
        })
    }

    override fun logout() {
        if (mDBApi != null) {
            mDBApi!!.session.unlink()
        }
        mPrefs!!.edit().remove(OAUTH2_TOKEN).commit()
    }

    override fun browseForNewFile(act: Activity, path: String, listener: FileStoreInterface.FileSelectedListener, txtOnly: Boolean) {
        if (!isOnline) {
            showToastLong(mApp, "Device is offline")
            log.info(TAG, "Device is offline, browse closed")
            return
        }
        val dialog = FileDialog(act, path, true)
        dialog.addFileListener(listener)
        dialog.createFileDialog(act, this)
    }

    @Synchronized @Throws(IOException::class)
    override fun saveTasksToFile(path: String, lines: List<String>, backup: BackupInterface?, eol: String) {
        backup?.backup(path, join(lines, "\n"))
        val contents = join(lines, eol) + eol
        val r = Runnable {
            var newName = path
            var rev = localTodoRev
            try {
                val toStore = contents.toByteArray(charset("UTF-8"))
                val `in` = ByteArrayInputStream(toStore)
                log.info(TAG, "Saving to file " + path)
                val newEntry = mDBApi!!.putFile(path, `in`,
                        toStore.size.toLong(), rev, null)
                rev = newEntry.rev
                newName = newEntry.path
                setChangesPending(false)
            } catch (e: Exception) {
                e.printStackTrace()
                // Changes are pending
                setChangesPending(true)
            } finally {
                // Always save to cache  so you wont lose changes
                // if actual save fails (e.g. when the device is offline)
                saveToCache(path, rev, contents)
            }

            if (newName != path) {
                // The file was written under another name
                // Usually this means the was a conflict.
                log.info(TAG, "Filename was changed remotely. New name is: " + newName)
                showToastLong(mApp, "Filename was changed remotely. New name is: " + newName)
                mFileChangedListerer.fileChanged(newName)
            }
        }
        queueRunnable("Save to file " + path, r)
    }

    @Throws(IOException::class)
    override fun appendTaskToFile(path: String, lines: List<String>, eol: String) {
        if (!isOnline) {
            throw IOException("Device is offline")
        }
        val r = Runnable {
            try {

                val doneContents = ArrayList<String>()
                var rev: String? = null
                // First read file to append to
                try {
                    val openFileStream = mDBApi!!.getFileStream(path, null)
                    val fileInfo = openFileStream.fileInfo
                    rev = fileInfo.metadata.rev
                    log.info(TAG, "The file's rev is: " + fileInfo.metadata.rev)
                    val reader = BufferedReader(InputStreamReader(openFileStream, "UTF-8"))

                    reader.forEachLine { line ->
                        doneContents.add(line)
                    }
                    openFileStream.close()

                } catch (e: DropboxException) {
                    log.info(TAG, "Couldn't read " + path)
                }

                // Then append
                for (t in lines) {
                    doneContents.add(t)
                }
                val toStore = (join(doneContents, eol) + eol).toByteArray(charset("UTF-8"))
                val `in` = ByteArrayInputStream(toStore)

                mDBApi!!.putFile(path, `in`,
                        toStore.size.toLong(), rev, null)
                `in`.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        queueRunnable("Append to file " + path, r)
    }

    override fun sync() {
        mFileChangedListerer.fileChanged(null)
    }

    @Throws(IOException::class)
    override fun readFile(file: String, fileRead: FileStoreInterface.FileReadListener?): String {
        if (!isAuthenticated) {
            return ""
        }
        isLoading = true
        try {
            val openFileStream = mDBApi!!.getFileStream(file, null)
            val fileInfo = openFileStream.fileInfo
            log.info(TAG, "The file's rev is: " + fileInfo.metadata.rev)

            val reader = BufferedReader(InputStreamReader(openFileStream, "UTF-8"))
            val readFile = ArrayList<String>()
            reader.forEachLine { line ->
                readFile.add(line)
            }
            openFileStream.close()
            val contents = join(readFile, "\n")
            fileRead?.fileRead(contents)
            return contents
        } catch (e: DropboxException) {
            throw IOException(e)
        } finally {
            isLoading = false
        }
    }

    override fun supportsSync(): Boolean {
        return true
    }

    override val type: Int
        get() = Constants.STORE_DROPBOX


    fun changedConnectionState(intent: Intent) {
        val prevOnline = mOnline
        mOnline = isOnline
        if (!prevOnline && mOnline) {
            // Schedule a task to reload the file
            // Give some time to settle so we ignore rapid connectivity changes
            // Only schedule if another thread is not running
            if (onOnline == null || !onOnline!!.isAlive) {
                queueRunnable("onOnline", Runnable {
                    // Check if we are still online
                    log.info(TAG, "Device went online, reloading in 5 seconds")
                    try {
                        Thread.sleep(5000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }

                    if (isOnline) {
                        continuePolling = true
                        mFileChangedListerer.fileChanged(null)
                    } else {
                        log.info(TAG, "Device no longer online skipping reload")
                    }
                })
            }

        } else if (!mOnline) {
            stopWatching()
        }
        if (prevOnline && !mOnline) {
            log.info(TAG, "Device went offline")
        }
    }

    override fun getWritePermission(act: Activity, activityResult: Int): Boolean {
        return true
    }

    /**
     * @param activity activity to display the file dialog.
     * *
     * @param pathName intial path shown in the file dialog
     */
    class FileDialog (private val activity: Activity, pathName: String, private val txtOnly: Boolean) {
        private val log: Logger
        private var fileList: Array<String>? = null
        private val entryHash = HashMap<String, DropboxAPI.Entry>()
        private var currentPath: File? = null

        private val fileListenerList = ListenerList<FileStoreInterface.FileSelectedListener>()
        internal var dialog: Dialog? = null
        private var loadingOverlay: Dialog? = null


        init {
            log = Logger
            currentPath = File(pathName)

        }

        /**

         */
        fun createFileDialog(act: Activity, fs: FileStoreInterface) {
            loadingOverlay = showLoadingOverlay(act, null, true)

            val api = (fs as FileStore).mDBApi ?: return

            // Use an asynctask because we need to manage the UI
            Thread(Runnable {
                loadFileList(act, api, currentPath)
                loadingOverlay = showLoadingOverlay(act, loadingOverlay, false)
                runOnMainThread(Runnable {
                    val builder = AlertDialog.Builder(activity)
                    builder.setTitle(currentPath!!.path)

                    builder.setItems(fileList, DialogInterface.OnClickListener { dialog, which ->
                        val fileChosen = fileList!![which]
                        if (fileChosen == PARENT_DIR) {
                            currentPath = File(currentPath!!.parent)
                            createFileDialog(act, fs)
                            return@OnClickListener
                        }
                        val chosenFile = getChosenFile(fileChosen)
                        log.warn(TAG, "Selected file " + chosenFile.name)
                        val entry = entryHash[fileChosen]
                        if (entry == null) {
                            dialog.dismiss()
                            return@OnClickListener
                        }
                        if (entry.isDir) {
                            currentPath = chosenFile
                            createFileDialog(act, fs)
                        } else {
                            dialog.cancel()
                            dialog.dismiss()
                            fireFileSelectedEvent(chosenFile)
                        }
                    })
                    if (dialog != null && dialog!!.isShowing) {
                        dialog!!.cancel()
                        dialog!!.dismiss()
                    }
                    dialog = builder.create()
                    dialog!!.show()
                })
            }).start()
        }


        fun addFileListener(listener: FileStoreInterface.FileSelectedListener) {
            fileListenerList.add(listener)
        }


        private fun fireFileSelectedEvent(file: File) {
            fileListenerList.fireEvent(object : ListenerList.FireHandler<FileStoreInterface.FileSelectedListener> {
                override fun fireEvent(listener: FileStoreInterface.FileSelectedListener) {
                    listener.fileSelected(file.toString())
                }
            })
        }

        @Throws(DropboxException::class)
        private fun getPathMetaData(api: DropboxAPI<AndroidAuthSession>?, path: File): DropboxAPI.Entry? {
            if (api != null) {
                return api.metadata(path.toString(), 0, null, true, null)
            } else {
                return null
            }
        }

        private fun loadFileList(act: Activity, api: DropboxAPI<AndroidAuthSession>, path: File?) {
            var path = path
            if (path == null) {
                path = File("/")
            }

            this.currentPath = path
            val f = ArrayList<String>()
            val d = ArrayList<String>()

            try {
                val entries = getPathMetaData(api, path)
                entryHash.clear()
                if (entries == null) {
                    return
                }
                if (!entries.isDir) return
                if (path.toString() != "/") {
                    d.add(PARENT_DIR)
                }
                for (entry in entries.contents) {
                    if (entry.isDeleted) continue
                    if (entry.isDir) {
                        d.add(entry.fileName())
                    } else {
                        if (txtOnly) {
                            if (!File(entry.fileName()).extension.equals("txt")) {
                                continue
                            }
                        }
                        f.add(entry.fileName())
                    }
                    entryHash.put(entry.fileName(), entry)
                }
            } catch (e: DropboxException) {
                log.warn(TAG, "Couldn't load list from " + path.name + " loading root instead.")
                loadFileList(act, api, null)
                return
            }

            Collections.sort(d)
            Collections.sort(f)
            d.addAll(f)
            fileList = d.toArray<String>(arrayOfNulls<String>(d.size))
        }

        private fun getChosenFile(fileChosen: String): File {
            if (fileChosen == PARENT_DIR)
                return currentPath!!.parentFile
            else
                return File(currentPath, fileChosen)
        }

        companion object {
            private val PARENT_DIR = ".."
        }
    }

    companion object {

        private val TAG = "FileStoreDB"

        private val LOCAL_CONTENTS = "localContents"
        private val LOCAL_NAME = "localName"
        private val LOCAL_CHANGES_PENDING = "localChangesPending"
        private val LOCAL_REVISION = "localRev"
        private val CACHE_PREFS = "dropboxMeta"
        private val OAUTH2_TOKEN = "dropboxToken"

        fun getDefaultPath(app: TodoApplication): String {
            if (app.fullDropBoxAccess) {
                return "/todo/todo.txt"
            } else {
                return "/todo.txt"
            }
        }
    }
}
