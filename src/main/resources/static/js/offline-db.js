const OfflineDB = (() => {
    const DB_NAME = "bluetech_offline";
    const DB_VERSION = 1;

    function open() {
        return new Promise((resolve, reject) => {
            const req = indexedDB.open(DB_NAME, DB_VERSION);

            req.onupgradeneeded = (event) => {
                const db = event.target.result;

                if (!db.objectStoreNames.contains("draft_results")) {
                    const store = db.createObjectStore("draft_results", { keyPath: "draftKey" });
                    store.createIndex("siteId", "siteId", { unique: false });
                    store.createIndex("syncStatus", "syncStatus", { unique: false });
                }

                if (!db.objectStoreNames.contains("draft_photos")) {
                    const store = db.createObjectStore("draft_photos", { keyPath: "photoKey" });
                    store.createIndex("draftKey", "draftKey", { unique: false });
                    store.createIndex("syncStatus", "syncStatus", { unique: false });
                }

                if (!db.objectStoreNames.contains("draft_locations")) {
                    const store = db.createObjectStore("draft_locations", { keyPath: "locationKey" });
                    store.createIndex("siteId", "siteId", { unique: false });
                    store.createIndex("syncStatus", "syncStatus", { unique: false });
                }
            };

            req.onsuccess = () => {
                resolve(req.result);
            };

            req.onerror = () => {
                reject(req.error || new Error("IndexedDB open failed"));
            };

            req.onblocked = () => {
                reject(new Error("IndexedDB open blocked"));
            };
        });
    }

    async function put(storeName, data) {
        const db = await open();

        return new Promise((resolve, reject) => {
            const tx = db.transaction(storeName, "readwrite");
            const store = tx.objectStore(storeName);
            const req = store.put(data);

            req.onerror = () => {
                reject(req.error || new Error(`${storeName} put failed`));
            };

            tx.onerror = () => {
                reject(tx.error || new Error(`${storeName} transaction failed`));
            };

            tx.onabort = () => {
                reject(tx.error || new Error(`${storeName} transaction aborted`));
            };

            tx.oncomplete = () => {
                db.close();
                resolve(true);
            };
        });
    }

    async function get(storeName, key) {
        const db = await open();

        return new Promise((resolve, reject) => {
            const tx = db.transaction(storeName, "readonly");
            const store = tx.objectStore(storeName);
            const req = store.get(key);

            req.onsuccess = () => {
                resolve(req.result || null);
            };

            req.onerror = () => {
                reject(req.error || new Error(`${storeName} get failed`));
            };

            tx.onerror = () => {
                reject(tx.error || new Error(`${storeName} transaction failed`));
            };

            tx.onabort = () => {
                reject(tx.error || new Error(`${storeName} transaction aborted`));
            };

            tx.oncomplete = () => {
                db.close();
            };
        });
    }

    async function getAll(storeName) {
        const db = await open();

        return new Promise((resolve, reject) => {
            const tx = db.transaction(storeName, "readonly");
            const store = tx.objectStore(storeName);
            const req = store.getAll();

            req.onsuccess = () => {
                resolve(req.result || []);
            };

            req.onerror = () => {
                reject(req.error || new Error(`${storeName} getAll failed`));
            };

            tx.onerror = () => {
                reject(tx.error || new Error(`${storeName} transaction failed`));
            };

            tx.onabort = () => {
                reject(tx.error || new Error(`${storeName} transaction aborted`));
            };

            tx.oncomplete = () => {
                db.close();
            };
        });
    }

    async function deleteByKey(storeName, key) {
        const db = await open();

        return new Promise((resolve, reject) => {
            const tx = db.transaction(storeName, "readwrite");
            const store = tx.objectStore(storeName);
            const req = store.delete(key);

            req.onerror = () => {
                reject(req.error || new Error(`${storeName} delete failed`));
            };

            tx.onerror = () => {
                reject(tx.error || new Error(`${storeName} transaction failed`));
            };

            tx.onabort = () => {
                reject(tx.error || new Error(`${storeName} transaction aborted`));
            };

            tx.oncomplete = () => {
                db.close();
                resolve(true);
            };
        });
    }

    async function exists(storeName, key) {
        const data = await get(storeName, key);
        return data !== null;
    }

    async function putAndVerify(storeName, data, keyField) {
        await put(storeName, data);

        const key = data[keyField];
        const saved = await get(storeName, key);

        if (!saved) {
            throw new Error(`${storeName} 저장 확인 실패`);
        }

        return saved;
    }

    async function clear(storeName) {
        const db = await open();

        return new Promise((resolve, reject) => {
            const tx = db.transaction(storeName, "readwrite");
            const store = tx.objectStore(storeName);
            const req = store.clear();

            req.onerror = () => {
                reject(req.error || new Error(`${storeName} clear failed`));
            };

            tx.onerror = () => {
                reject(tx.error || new Error(`${storeName} transaction failed`));
            };

            tx.onabort = () => {
                reject(tx.error || new Error(`${storeName} transaction aborted`));
            };

            tx.oncomplete = () => {
                db.close();
                resolve(true);
            };
        });
    }

    return {
        open,
        put,
        get,
        getAll,
        deleteByKey,
        exists,
        putAndVerify,
        clear
    };
})();