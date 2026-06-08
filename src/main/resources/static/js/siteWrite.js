let isSyncing = false;
let isAutoSavingBeforeMove = false;

document.addEventListener("click", function (e) {
    if (isPhotoPickerBlocked() && e.target.closest(".photo-slot")) {
        e.preventDefault();
        e.stopImmediatePropagation();
        return false;
    }

    if (e.target.closest('input[type="file"]')) {
        e.stopImmediatePropagation();
        return;
    }

    if (e.target.closest(".photo-action-row")) {
        e.stopImmediatePropagation();
        return;
    }

    if (e.target.closest(".photo-upload-btn")) {
        e.stopImmediatePropagation();
        return;
    }
}, true);

let photoPickerBlockUntil = 0;

function blockPhotoPicker(ms = 1500) {
    photoPickerBlockUntil = Date.now() + ms;
}

function isPhotoPickerBlocked() {
    return isAutoSavingBeforeMove || Date.now() < photoPickerBlockUntil;
}

function setSyncLoading(show, text = "잠시만 기다려주세요.") {
    const modal = document.getElementById("syncStatusModal");
    const subtext = document.getElementById("syncStatusSubtext");
    const title = document.getElementById("syncStatusTitle");
    const closeBtn = document.getElementById("syncStatusCloseBtn");
    const syncBtn = document.querySelector(".sync-btn");

    if (subtext) subtext.textContent = text;

    if (title && show) {
        title.textContent = "동기화 중입니다...";
    }

    if (closeBtn && show) {
        closeBtn.disabled = true;
    }

    if (syncBtn) {
        syncBtn.disabled = show;
        syncBtn.textContent = show ? "동기화 중..." : "동기화";
    }

    if (modal) {
        modal.style.display = show ? "flex" : "none";
    }
}

async function updatePendingSyncBadge() {
    const siteId = Number($("#siteId").val());
    const syncBtn = document.querySelector(".sync-btn");
    if (!syncBtn) return;

    const results = (await OfflineDB.getAll("draft_results"))
        .filter(x => x.siteId === siteId && x.syncStatus === "pending");

    const totalCount = results.length;

    syncBtn.textContent = totalCount > 0 ? `동기화 (${totalCount})` : "동기화";
}

function finishSyncStatus(success, message) {
    const title = document.getElementById("syncStatusTitle");
    const subtext = document.getElementById("syncStatusSubtext");
    const closeBtn = document.getElementById("syncStatusCloseBtn");
    const syncBtn = document.querySelector(".sync-btn");

    if (title) {
        title.textContent = success ? "동기화 완료" : "동기화 실패";
    }

    if (subtext) {
        subtext.textContent = message || (success ? "완료 버튼을 눌러 닫아주세요." : "문제가 발생했습니다. 다시 시도해주세요.");
    }

    if (closeBtn) {
        closeBtn.disabled = false;
    }

    if (syncBtn) {
        syncBtn.disabled = false;
        syncBtn.textContent = "동기화";
    }
}


function closeSyncStatusModal() {
    if (isSyncing) return;

    const modal = document.getElementById("syncStatusModal");
    if (modal) {
        modal.style.display = "none";
    }
}

async function toggleCategory(button) {
    const ok = await confirmAndSaveIfNeeded();
    if (!ok) return;

    const box = button.closest(".category-box");
    const inner = box.querySelector(".category-inner");
    const isOpen = inner.classList.contains("open");

    document.querySelectorAll(".category-inner.open").forEach(el => {
        el.classList.remove("open");
    });

    if (!isOpen && inner) {
        inner.classList.add("open");
    }
}

function makeDraftKey(siteId, itemId, categoryGroup) {
    return `${siteId}_${itemId}_${categoryGroup}`;
}

function getCurrentOpenItemCard() {
    const openEditor = document.querySelector(".item-editor.open");
    return openEditor ? openEditor.closest(".item-card") : null;
}

function getPhotoCountFromEditor(editor) {
    if (!editor) return 0;

    return Array.from(editor.querySelectorAll(".photo-slot")).filter(slot => {
        const cameraInput = slot.querySelector(".inline-photo-input");
        const galleryInput = slot.querySelector(".inline-gallery-input");

        const hasCameraFile = !!(cameraInput && cameraInput.files && cameraInput.files.length > 0);
        const hasGalleryFile = !!(galleryInput && galleryInput.files && galleryInput.files.length > 0);
        const hasSavedPhoto = !!slot.dataset.savedPhotoId;
        const hasLocalPhoto = !!slot.dataset.localPhotoKey;
        const hasPreview = slot.classList.contains("filled");

        return hasCameraFile || hasGalleryFile || hasSavedPhoto || hasLocalPhoto || hasPreview;
    }).length;
}

async function hasUnsavedChanges(itemCard) {
    if (!itemCard) return false;

    const siteId = $("#siteId").val();
    const saveBtn = itemCard.querySelector(".inline-save-btn");
    const editor = itemCard.querySelector(".item-editor");
    if (!saveBtn || !editor) return false;

    const itemId = saveBtn.dataset.itemId;
    const categoryGroup = saveBtn.dataset.categoryGroup;
    const draftKey = makeDraftKey(siteId, itemId, categoryGroup);

    mergeSpecialVisibleMemo(editor);
    const currentMemo = getMemoInputForSave(editor)?.value.trim() || "";
    const currentPhotoCount = getPhotoCountFromEditor(editor);
    const currentResult = itemCard.dataset.currentResult || "미작성";

    let serverRes = {
        memo: "",
        result: "미작성",
        photos: []
    };

    try {
        serverRes = await $.ajax({
            type: "GET",
            url: "/inspection/detail",
            data: { siteId, itemId, categoryGroup }
        });
    } catch (e) {
        console.warn("오프라인 상태라 서버 변경사항 조회 생략", e);
    }

    const localDraft = await OfflineDB.get("draft_results", draftKey);
    const allLocalPhotos = await OfflineDB.getAll("draft_photos");

    const localPhotos = allLocalPhotos.filter(photo =>
        photo.draftKey === draftKey && photo.isDeleted !== true
    );

    const deletedServerPhotoIds = allLocalPhotos
        .filter(photo => photo.isDeleted === true && photo.serverPhotoId)
        .map(photo => Number(photo.serverPhotoId));

    const serverPhotos = (serverRes.photos || [])
        .filter(photo => !deletedServerPhotoIds.includes(Number(photo.id)));

    const mergedSavedPhotoCount = serverPhotos.length + localPhotos.length;
    const savedMemo = localDraft ? (localDraft.memo || "") : (serverRes.memo || "");
    const savedResult = localDraft ? (localDraft.result || "미작성") : (serverRes.result || "미작성");

    const memoChanged = currentMemo !== savedMemo;
    const photoChanged = currentPhotoCount !== mergedSavedPhotoCount;
    const resultChanged = currentResult !== savedResult;

    return memoChanged || photoChanged || resultChanged;
}

async function openItemEditor(button) {
    const card = button.closest(".item-card");
    const editor = card.querySelector(".item-editor");

    document.querySelectorAll(".item-editor.open").forEach(el => {
        el.classList.remove("open");
    });

    document.querySelectorAll(".item-toggle-btn").forEach(btn => {
        btn.textContent = "열기";
    });

    editor.classList.add("open");
    button.textContent = "열기";

    await loadInlineInspectionData(button, editor);

    setTimeout(() => {
        card.scrollIntoView({behavior: "smooth", block: "start"});
    }, 80);
}

async function saveInlineInspection(button, options = {}) {
    const { silent = false } = options;
    if (button.dataset.saving === "true") return false;

    const siteId = $("#siteId").val();
    const itemId = button.dataset.itemId;
    const categoryGroup = button.dataset.categoryGroup;
    const editor = button.closest(".item-editor");

    mergeSpecialVisibleMemo(editor);

    const memoInput = getMemoInputForSave(editor);
    const memo = memoInput?.value.trim() || "";

    const visibleMemoInput = getVisibleMemoInput(editor);
    const visibleMemo = visibleMemoInput?.value.trim() || "";

    const itemCard = button.closest(".item-card");
    const itemRow = itemCard.querySelector(".item-row");
    const currentResult = itemCard.dataset.currentResult || "미작성";
    const draftKey = makeDraftKey(siteId, itemId, categoryGroup);

    const originalText = button.textContent;
    button.dataset.saving = "true";
    button.disabled = true;
    button.textContent = "로컬저장중...";

    try {
        const hasPhoto = Array.from(editor.querySelectorAll(".photo-slot"))
            .some(slot =>
                !!slot.dataset.savedPhotoId ||
                !!slot.dataset.localPhotoKey ||
                slot.classList.contains("filled")
            );

        let finalResult = "미작성";

        if (currentResult === "해당사항없음") {
            finalResult = "해당사항없음";
        } else if (memo !== "" || visibleMemo !== "" || hasPhoto) {
            finalResult = "작성";
        }

        await OfflineDB.putAndVerify("draft_results", {
            draftKey,
            siteId: Number(siteId),
            itemId: Number(itemId),
            categoryGroup,
            result: finalResult,
            memo,
            updatedAt: new Date().toISOString(),
            syncStatus: "pending"
        }, "draftKey");

        itemCard.dataset.currentResult = finalResult;

        if (finalResult === "작성" || finalResult === "해당사항없음") {
            itemRow?.classList.add("done");
        } else {
            itemRow?.classList.remove("done");
        }

        syncStatusButton(itemCard);

        const locationBox = itemCard.closest(".location-box");
        if (locationBox) updateLocationProgress(locationBox);

        recalcSiteProgress();
        await refreshVisibleItemStates();
        await updatePendingSyncBadge();

        if (!silent) {
            alert("로컬 저장 완료");
        }

        return true;
    } catch (e) {
        console.error(e);
        alert("로컬 저장 실패");
        return false;
    } finally {
        button.dataset.saving = "false";
        button.disabled = false;
        button.textContent = originalText;
    }
}

function updateSyncProgress(done, total, text = "") {
    const percent = total <= 0 ? 0 : Math.min(100, Math.round((done / total) * 100));
    const fill = document.getElementById("syncProgressFill");
    const progressText = document.getElementById("syncProgressText");
    const subtext = document.getElementById("syncStatusSubtext");

    if (fill) {
        fill.style.width = `${percent}%`;
    }

    if (progressText) {
        progressText.textContent = total > 0 ? `${percent}% (${done}/${total})` : "0%";
    }

    if (subtext && text) {
        subtext.textContent = text;
    }
}

function updateCategoryLocationCount(categoryBox) {
    if (!categoryBox) return;

    const titleSpan = categoryBox.querySelector(".category-title span");
    if (!titleSpan) return;

    const locationCount = categoryBox.querySelectorAll(".location-box").length;
    const currentText = titleSpan.textContent.trim();
    const baseText = currentText.replace(/\s*\(\d+\)\s*$/, "").trim();

    titleSpan.textContent = `${baseText} (${locationCount})`;
}

function updateAllCategoryLocationCounts() {
    document.querySelectorAll(".category-box").forEach(categoryBox => {
        updateCategoryLocationCount(categoryBox);
    });
}

function updateLocationProgress(locationBox) {
    if (!locationBox) return;

    const total = locationBox.querySelectorAll(".item-row").length;
    const completed = Array.from(locationBox.querySelectorAll(".item-card"))
        .filter(card => {
            const val = card.dataset.currentResult || "미작성";
            return val === "작성" || val === "해당사항없음";
        }).length;

    const progress = locationBox.querySelector(".location-progress");
    if (progress) {
        progress.textContent = `${completed}/${total}`;
    }
}

function syncStatusButton(itemCard) {
    if (!itemCard) return;

    const statusBtn = itemCard.querySelector(".status-default-btn");
    if (!statusBtn) return;

    const currentResult = itemCard.dataset.currentResult || "미작성";

    if (currentResult === "작성") {
        statusBtn.textContent = "작성";
        statusBtn.style.backgroundColor = "#16a34a";
        statusBtn.style.color = "#ffffff";
        statusBtn.style.borderColor = "#16a34a";
    } else if (currentResult === "해당사항없음") {
        statusBtn.textContent = "해당사항없음";
        statusBtn.style.backgroundColor = "#6b7280";
        statusBtn.style.color = "#ffffff";
        statusBtn.style.borderColor = "#6b7280";
    } else {
        statusBtn.textContent = "미작성";
        statusBtn.style.backgroundColor = "";
        statusBtn.style.color = "";
        statusBtn.style.borderColor = "";
    }
}

async function refreshVisibleItemStates() {
    const siteId = $("#siteId").val();
    const allDrafts = await OfflineDB.getAll("draft_results");
    const allPhotos = await OfflineDB.getAll("draft_photos");

    document.querySelectorAll(".item-card").forEach(itemCard => {
        const saveBtn = itemCard.querySelector(".inline-save-btn");
        if (!saveBtn) return;

        const itemId = saveBtn.dataset.itemId;
        const categoryGroup = saveBtn.dataset.categoryGroup;
        const draftKey = makeDraftKey(siteId, itemId, categoryGroup);

        const draft = allDrafts.find(x => x.draftKey === draftKey);
        const localPhotos = allPhotos.filter(x => x.draftKey === draftKey && x.isDeleted !== true);

        const row = itemCard.querySelector(".item-row");

        // 로컬 변경이 전혀 없으면 서버가 렌더한 현재 상태를 유지
        if (!draft && localPhotos.length === 0) {
            syncStatusButton(itemCard);
            return;
        }

        const editor = itemCard.querySelector(".item-editor");
        const memoInput = editor?.querySelector(".inline-memo");
        const memoValue = draft ? (draft.memo || "") : (memoInput?.value?.trim() || "");

        const hasPhoto = localPhotos.length > 0 ||
            Array.from(itemCard.querySelectorAll(".photo-slot")).some(slot =>
                !!slot.dataset.savedPhotoId || !!slot.dataset.localPhotoKey || slot.classList.contains("filled")
            );

        if (draft?.result === "해당사항없음") {
            itemCard.dataset.currentResult = "해당사항없음";
        } else if (memoValue !== "" || hasPhoto) {
            itemCard.dataset.currentResult = "작성";
        } else {
            itemCard.dataset.currentResult = "미작성";
        }

        if (row) {
            if (itemCard.dataset.currentResult === "작성" || itemCard.dataset.currentResult === "해당사항없음") {
                row.classList.add("done");
            } else {
                row.classList.remove("done");
            }
        }

        syncStatusButton(itemCard);
    });

    document.querySelectorAll(".location-box").forEach(updateLocationProgress);
    recalcSiteProgress();
}

function recalcSiteProgress() {
    const siteNameBox = document.querySelector(".site-name");
    const categoryBoxes = document.querySelectorAll(".category-box");
    const fixedTotal = Number(document.getElementById("siteTotalCount")?.value || categoryBoxes.length);

    let siteCompleted = 0;

    categoryBoxes.forEach(categoryBox => {
        const hasAnyInputInCategory = Array.from(categoryBox.querySelectorAll(".item-card"))
            .some(itemCard => {
                const val = itemCard.dataset.currentResult || "미작성";
                return val === "작성" || val === "해당사항없음";
            });

        if (hasAnyInputInCategory) {
            siteCompleted++;
        }
    });

    if (siteNameBox) {
        const siteTitle = document.querySelector(".page-title")
            ?.textContent?.replace(" 현장 점검 작성", "") || "현장";

        siteNameBox.textContent = `${siteTitle} 현장 (${siteCompleted}/${fixedTotal})`;
    }
}

async function toggleLocation(button) {
    const ok = await confirmAndSaveIfNeeded();
    if (!ok) return;

    const locationBox = button.closest(".location-box");
    const itemList = locationBox.querySelector(".item-list");
    const isOpen = itemList.classList.contains("open");

    const locationList = locationBox.closest(".location-list");
    if (locationList) {
        locationList.querySelectorAll(".item-list.open").forEach(el => {
            el.classList.remove("open");
        });
    }

    if (!isOpen && itemList) {
        itemList.classList.add("open");
    }
}

function toggleItemEditor(button) {
    const card = button.closest(".item-card");
    const editor = card.querySelector(".item-editor");
    const isOpen = editor.classList.contains("open");

    document.querySelectorAll(".item-editor.open").forEach(el => {
        el.classList.remove("open");
    });

    if (!isOpen) {
        editor.classList.add("open");
        loadInlineInspectionData(button, editor);
    }
}

async function addLocation(button) {
    const ok = await confirmAndSaveIfNeeded();
    if (!ok) return;

    const siteId = $("#siteId").val();
    const templateCategory = button.dataset.templateCategory;
    const categoryBox = button.closest(".category-box");
    const input = categoryBox.querySelector(".location-name-input");
    const newLocationName = input.value.trim();

    if (!newLocationName) {
        alert("위치명을 입력해주세요.");
        return;
    }

    if (newLocationName.includes("_")) {
        alert("위치명에는 _ 문자를 사용할 수 없습니다.");
        return;
    }

    $.ajax({
        type: "POST",
        url: "/category/add",
        data: {
            siteId,
            templateCategory,
            newCategoryName: newLocationName
        },
        success: function () {
            $.ajax({
                type: "GET",
                url: "/category/location-list",
                data: {
                    siteId,
                    categoryName: templateCategory
                },
                    success: async function (html) {
                        const oldList = categoryBox.querySelector(".location-list");
                        const categoryInner = categoryBox.querySelector(".category-inner");
                        const addBox = categoryInner.querySelector(".location-add-box");

                        if (oldList) {
                            oldList.outerHTML = html;
                        } else {
                            addBox.insertAdjacentHTML("afterend", html);
                        }

                        input.value = "";

                        categoryBox.querySelectorAll(".item-editor").forEach(bindMemoAutoWrite);
                        categoryBox.querySelectorAll(".photo-slot").forEach(bindInlinePhotoSlot);

                        await refreshVisibleItemStates();
                        await updatePendingSyncBadge();

                        updateCategoryLocationCount(categoryBox);
                        recalcSiteProgress();

                        const inner = categoryBox.querySelector(".category-inner");
                        if (inner) {
                            inner.classList.add("open");
                        }
                    },
                error: function (xhr) {
                    console.error(xhr.responseText);
                    alert("위치 목록 갱신 실패");
                }
            });
        },
        error: function (xhr) {
            console.error(xhr.responseText);
            alert("위치 추가 실패");
        }
    });
}

async function deleteLocation(button) {
    const siteId = $("#siteId").val();
    const categoryGroup = button.dataset.categoryGroup;
    const locationBox = button.closest(".location-box");
    const categoryBox = button.closest(".category-box");

    if (button.dataset.deleting === "true") return;

    try {
        const summary = await getLocationDeleteSummary(siteId, categoryGroup, locationBox);
        const photoCount = summary.photoCount || 0;
        const memoCount = summary.memoCount || 0;

        let message = "이 위치를 삭제할까요?";

        if (photoCount > 0 || memoCount > 0) {
            message = `이 위치에는 사진 ${photoCount}건, 메모 ${memoCount}건이 있습니다.\n삭제하시겠습니까?`;
        }

        if (!confirm(message)) return;
    } catch (e) {
        console.error(e);
        alert("삭제 전 데이터 확인 중 오류가 발생했습니다.");
        return;
    }

    const originalText = button.textContent;
    button.dataset.deleting = "true";
    button.disabled = true;
    button.textContent = "삭제중...";

    $.ajax({
        type: "POST",
        url: "/category/delete",
        data: { siteId, categoryGroup },
        success: async function () {
            try {
                const localDraftResults = await OfflineDB.getAll("draft_results");
                const localDraftPhotos = await OfflineDB.getAll("draft_photos");

                const targetDrafts = localDraftResults.filter(d =>
                    Number(d.siteId) === Number(siteId) &&
                    d.categoryGroup === categoryGroup
                );

                const targetDraftKeys = targetDrafts.map(d => d.draftKey);

                for (const draft of targetDrafts) {
                    await OfflineDB.deleteByKey("draft_results", draft.draftKey);
                }

                for (const photo of localDraftPhotos) {
                    if (targetDraftKeys.includes(photo.draftKey)) {
                        await OfflineDB.deleteByKey("draft_photos", photo.photoKey);
                    }
                }

                if (locationBox) {
                    locationBox.remove();
                }

                updateCategoryLocationCount(categoryBox);
                recalcSiteProgress();
                await updatePendingSyncBadge();

                alert("삭제되었습니다.");
            } catch (cleanupError) {
                console.error(cleanupError);
                alert("서버 삭제는 완료되었지만 로컬 데이터 정리 중 오류가 발생했습니다.");
            }
        },
        error: function (xhr) {
            console.error(xhr.responseText);
            alert("위치 삭제 실패");
        },
        complete: function () {
            button.dataset.deleting = "false";
            button.disabled = false;
            button.textContent = originalText;
        }
    });
}

function bindInlinePhotoSlot(slot) {
    const cameraInput = slot.querySelector(".inline-photo-input");
    const galleryInput = slot.querySelector(".inline-gallery-input");
    const img = slot.querySelector(".photo-slot-img");
    const text = slot.querySelector(".photo-slot-text");
    const delBtn = slot.querySelector(".photo-delete-btn");

    let badge = slot.querySelector(".photo-save-badge");
    if (!badge) {
        badge = document.createElement("span");
        badge.className = "photo-save-badge";
        badge.textContent = "미저장";
        badge.style.display = "none";
        slot.appendChild(badge);
    }

    async function handleSelectedFile(input) {
        const file = input.files && input.files[0];
        if (!file) return;

        const editor = slot.closest(".item-editor");
        const saveBtn = editor?.querySelector(".inline-save-btn");

        if (!saveBtn) {
            alert("저장 대상 항목을 찾지 못했습니다.");
            input.value = "";
            return;
        }

        const siteId = $("#siteId").val();
        const itemId = saveBtn.dataset.itemId;
        const categoryGroup = saveBtn.dataset.categoryGroup;
        const draftKey = makeDraftKey(siteId, itemId, categoryGroup);

        try {
            markPhotoSlotPending(slot);

            const savedPhotoId = slot.dataset.savedPhotoId;

            if (savedPhotoId) {
                await OfflineDB.putAndVerify("draft_photos", {
                    photoKey: `delete_${savedPhotoId}`,
                    serverPhotoId: Number(savedPhotoId),
                    isDeleted: true,
                    syncStatus: "pending",
                    createdAt: new Date().toISOString()
                }, "photoKey");
            }

            if (slot.dataset.localPhotoKey) {
                await OfflineDB.deleteByKey("draft_photos", slot.dataset.localPhotoKey);
                delete slot.dataset.localPhotoKey;
            }

            const photoKey = generateUUID();

            await OfflineDB.putAndVerify("draft_photos", {
                photoKey,
                draftKey,
                fileBlob: file,
                fileName: file.name || "photo.jpg",
                isDeleted: false,
                syncStatus: "pending",
                createdAt: new Date().toISOString()
            }, "photoKey");

            slot.dataset.localPhotoKey = photoKey;
            delete slot.dataset.savedPhotoId;

            const previewUrl = URL.createObjectURL(file);

            if (img.dataset.objectUrl) {
                URL.revokeObjectURL(img.dataset.objectUrl);
            }

            img.dataset.objectUrl = previewUrl;
            img.src = previewUrl;
            img.style.display = "block";

            if (text) text.style.display = "none";
            if (delBtn) delBtn.style.display = "flex";

            slot.classList.remove("empty");
            slot.classList.add("filled");

            input.value = "";
            if (cameraInput && cameraInput !== input) cameraInput.value = "";
            if (galleryInput && galleryInput !== input) galleryInput.value = "";

            const saved = await saveInlineInspection(saveBtn, {
                silent: true,
                keepOpen: true
            });

            if (!saved) {
                throw new Error("점검 결과 로컬 저장 실패");
            }

            markPhotoSlotSaved(slot);

            const itemCard = saveBtn.closest(".item-card");
            if (itemCard) {
                itemCard.dataset.currentResult = "작성";
                itemCard.querySelector(".item-row")?.classList.add("done");
                syncStatusButton(itemCard);

                const locationBox = itemCard.closest(".location-box");
                if (locationBox) updateLocationProgress(locationBox);
            }

            recalcSiteProgress();
            await updatePendingSyncBadge();

        } catch (e) {
            console.error(e);
            markPhotoSlotFailed(slot);
            alert("사진 로컬 저장 실패");
        }
    }

    if (cameraInput) {
        cameraInput.onchange = function () {
            handleSelectedFile(cameraInput);
        };
    }

    if (galleryInput) {
        galleryInput.onchange = function () {
            handleSelectedFile(galleryInput);
        };
    }
}


async function removeInlinePhoto(event, button) {
    event.stopPropagation();

    if (!confirm("이 사진을 삭제하시겠습니까?")) {
        return;
    }

    if (button.dataset.deleting === "true") return;

    const slot = button.closest(".photo-slot");
    const localPhotoKey = slot.dataset.localPhotoKey;
    const savedPhotoId = slot.dataset.savedPhotoId;

    const originalText = button.textContent;
    button.dataset.deleting = "true";
    button.disabled = true;
    button.textContent = "...";

    try {
        if (localPhotoKey) {
            await OfflineDB.deleteByKey("draft_photos", localPhotoKey);
            clearInlineSlot(slot);
            await updatePendingSyncBadge();
            alert("사진이 삭제되었습니다.");
            return;
        }

        if (savedPhotoId) {
            await OfflineDB.put("draft_photos", {
                photoKey: `delete_${savedPhotoId}`,
                serverPhotoId: Number(savedPhotoId),
                isDeleted: true,
                syncStatus: "pending",
                createdAt: new Date().toISOString()
            });
            clearInlineSlot(slot);
            await updatePendingSyncBadge();
            alert("사진이 삭제 예약되었습니다. 동기화 시 서버에서도 삭제됩니다.");
            return;
        }

        clearInlineSlot(slot);
        alert("사진이 삭제되었습니다.");
    } catch (e) {
        console.error(e);
        alert("사진 삭제 처리 실패");
    } finally {
        button.dataset.deleting = "false";
        button.disabled = false;
        button.textContent = originalText;
    }
}

function clearInlineSlot(slot) {
    const cameraInput = slot.querySelector(".inline-photo-input");
    const galleryInput = slot.querySelector(".inline-gallery-input");
    const img = slot.querySelector(".photo-slot-img");
    const text = slot.querySelector(".photo-slot-text");
    const delBtn = slot.querySelector(".photo-delete-btn");

    if (cameraInput) cameraInput.value = "";
    if (galleryInput) galleryInput.value = "";

    if (img) {
        if (img.dataset.objectUrl) {
            URL.revokeObjectURL(img.dataset.objectUrl);
            delete img.dataset.objectUrl;
        }

        img.src = "";
        img.style.display = "none";
    }

    if (text) text.style.display = "block";
    if (delBtn) delBtn.style.display = "none";

    const badge = slot.querySelector(".photo-save-badge");

    if (badge) {
        badge.textContent = "미저장";
        badge.style.display = "none";
    }

    slot.classList.remove("local-saved", "local-pending");

    slot.classList.add("empty");
    slot.classList.remove("filled");

    delete slot.dataset.savedPhotoId;
    delete slot.dataset.localPhotoKey;
}


function ensurePhotoSlots(grid, count) {
    const slots = Array.from(grid.querySelectorAll(".photo-slot"));

    while (slots.length < Math.max(2, count)) {
        const div = createPhotoSlot();
        grid.appendChild(div);
        bindInlinePhotoSlot(div);
        slots.push(div);
    }

    return slots;
}

function renderAllPhotos(editor, photoItems) {
    const grid = editor.querySelector(".editor-photo-grid");
    if (!grid) return;

    const list = photoItems || [];
    const slots = ensurePhotoSlots(grid, list.length);

    slots.forEach(slot => clearInlineSlot(slot));

    list.forEach((photo, index) => {
        const slot = slots[index];
        const img = slot.querySelector(".photo-slot-img");
        const text = slot.querySelector(".photo-slot-text");
        const delBtn = slot.querySelector(".photo-delete-btn");

        let src = "";

        if (photo.type === "local") {
            src = URL.createObjectURL(photo.fileBlob);
            img.dataset.objectUrl = src;
            slot.dataset.localPhotoKey = photo.photoKey;
        } else {
            src = photo.fileUrl || "";
            slot.dataset.savedPhotoId = photo.id;
        }

        img.src = src;
        img.style.display = "block";
        text.style.display = "none";
        delBtn.style.display = "flex";

        slot.classList.remove("empty");
        slot.classList.add("filled");

        markPhotoSlotSaved(slot);
    });
}

function addInlinePhotoSlot(button) {
    const editor = button.closest(".item-editor");
    const grid = editor.querySelector(".editor-photo-grid");

    const div = createPhotoSlot();
    grid.appendChild(div);
    bindInlinePhotoSlot(div);
}

async function loadInlineInspectionData(button, editor) {
    const siteId = $("#siteId").val();
    const itemId = button.dataset.itemId;
    const categoryGroup = button.dataset.categoryGroup;
    const draftKey = makeDraftKey(siteId, itemId, categoryGroup);

    const itemCard = button.closest(".item-card");

    let serverRes = {
        memo: "",
        result: "미작성",
        photos: []
    };

    try {
        serverRes = await $.ajax({
            type: "GET",
            url: "/inspection/detail",
            data: { siteId, itemId, categoryGroup }
        });
    } catch (e) {
        console.warn("오프라인 또는 서버 조회 실패. 로컬 데이터로만 표시합니다.", e);
    }

    try {
        const localDraft = await OfflineDB.get("draft_results", draftKey);
        const allLocalPhotos = await OfflineDB.getAll("draft_photos");

        const localPhotos = allLocalPhotos.filter(photo =>
            photo.draftKey === draftKey &&
            photo.isDeleted !== true
        );

        const deletedServerPhotoIds = allLocalPhotos
            .filter(photo => photo.isDeleted === true && photo.serverPhotoId)
            .map(photo => Number(photo.serverPhotoId));

        const serverPhotos = (serverRes.photos || [])
            .filter(photo => !deletedServerPhotoIds.includes(Number(photo.id)));

        const mergedPhotos = [
            ...serverPhotos.map(photo => ({
                type: "server",
                id: photo.id,
                fileUrl: photo.fileUrl || photo.file_url || photo.url || ""
            })),
            ...localPhotos.map(photo => ({
                type: "local",
                photoKey: photo.photoKey,
                fileBlob: photo.fileBlob,
                fileName: photo.fileName
            }))
        ];

        const finalMemo = localDraft ? (localDraft.memo || "") : (serverRes.memo || "");
        const finalResult = localDraft ? (localDraft.result || "미작성") : (serverRes.result || "미작성");

        const normalMemo = getVisibleMemoInput(editor);
        if (normalMemo) {
            normalMemo.value = finalMemo;
            normalMemo.disabled = false;
            normalMemo.readOnly = false;
        }

        if (finalMemo && finalMemo.includes('"type":"airflowSheet"')) {
            restoreAirflowSheet(editor, finalMemo);
        }

        if (finalMemo && finalMemo.includes('"type":"efficiencySheet"')) {
            restoreEfficiencySheet(editor, finalMemo);
        }

        if (finalMemo && finalMemo.includes('"type":"fanControlSheet"')) {
            restoreFanControlSheet(editor, finalMemo);
        }

        renderAllPhotos(editor, mergedPhotos);

        const hasPhoto = mergedPhotos.length > 0;
        const hasMemo = !!(finalMemo && finalMemo.trim() !== "");

        if (finalResult === "해당사항없음") {
            itemCard.dataset.currentResult = "해당사항없음";
        } else if (hasMemo || hasPhoto || finalResult === "작성") {
            itemCard.dataset.currentResult = "작성";
        } else {
            itemCard.dataset.currentResult = "미작성";
        }

        const row = itemCard.querySelector(".item-row");
        if (row) {
            if (itemCard.dataset.currentResult === "작성" || itemCard.dataset.currentResult === "해당사항없음") {
                row.classList.add("done");
            } else {
                row.classList.remove("done");
            }
        }

        syncStatusButton(itemCard);

    } catch (e) {
        console.error(e);
        alert("로컬 점검 정보 표시 중 오류가 발생했습니다.");
    }
}


document.addEventListener("DOMContentLoaded", function () {
    document.querySelectorAll(".item-editor").forEach(bindMemoAutoWrite);
    document.querySelectorAll(".photo-slot").forEach(bindInlinePhotoSlot);
    document.querySelectorAll(".item-card").forEach(syncStatusButton);

    document.querySelectorAll(".status-direct-wrap .item-card").forEach(card => {

        const editor = card.querySelector(".item-editor");

        const fakeBtn = {
            dataset: {
                itemId: card.querySelector(".inline-save-btn").dataset.itemId,
                categoryGroup: card.querySelector(".inline-save-btn").dataset.categoryGroup
            },
            closest: () => card
        };

        loadInlineInspectionData(fakeBtn, editor);
    });


    const logoutBtn = document.getElementById("logoutBtn");
    if (logoutBtn) {
        logoutBtn.addEventListener("click", function () {
            $.ajax({
                type: "POST",
                url: "/logout",
                success: function () {
                    alert("로그아웃되었습니다.");
                    window.location.href = "/";
                },
                error: function () {
                    alert("로그아웃 실패");
                }
            });
        });
    }

    const deleteSiteBtn = document.getElementById("deleteSiteBtn");
    if (deleteSiteBtn) {
        deleteSiteBtn.addEventListener("click", function () {
            if (deleteSiteBtn.dataset.deleting === "true") return;

            const siteId = document.getElementById("siteId").value;

            if (!confirm("정말 이 현장을 삭제하시겠습니까?")) return;

            const confirmText = prompt("삭제하려면 '삭제' 라고 입력하세요");
            if (confirmText !== "삭제") {
                alert("입력이 일치하지 않아 취소되었습니다.");
                return;
            }

            const originalText = deleteSiteBtn.textContent;
            deleteSiteBtn.dataset.deleting = "true";
            deleteSiteBtn.disabled = true;
            deleteSiteBtn.textContent = "삭제중...";

            $.ajax({
                type: "POST",
                url: "/site/delete",
                data: {siteId},
                success: function () {
                    alert("현장이 삭제되었습니다.");
                    window.location.href = "/loginOk";
                },
                error: function (xhr) {
                    console.error(xhr.responseText);
                    alert("삭제 실패");
                },
                complete: function () {
                    deleteSiteBtn.dataset.deleting = "false";
                    deleteSiteBtn.disabled = false;
                    deleteSiteBtn.textContent = originalText;
                }
            });
        });
    }

    refreshVisibleItemStates();
    updatePendingSyncBadge();
});

let currentResultTarget = null;

function openResultModal(button) {
    currentResultTarget = button;

    const currentValue = button.dataset.currentResult || "미작성";
    const modal = document.getElementById("resultModal");

    document.querySelectorAll("input[name='resultOption']").forEach(radio => {
        radio.checked = (radio.value === currentValue);
    });

    modal.style.display = "flex";
}

function closeResultModal() {
    const modal = document.getElementById("resultModal");
    modal.style.display = "none";
    currentResultTarget = null;
}

function applyResultModal() {
    if (!currentResultTarget) return;

    const checked = document.querySelector("input[name='resultOption']:checked");
    if (!checked) {
        alert("상태를 선택해주세요.");
        return;
    }

    const value = checked.value;
    currentResultTarget.dataset.currentResult = value;
    currentResultTarget.textContent = value;

    if (value === "해당사항없음") {
        const itemCard = currentResultTarget.closest(".item-card");
        const saveBtn = itemCard.querySelector(".inline-save-btn");

        if (saveBtn) {
            saveInlineInspection(saveBtn);
        }
    }

    closeResultModal();
}

async function confirmAndSaveIfNeeded() {
    const currentOpenCard = getCurrentOpenItemCard();
    if (!currentOpenCard) return true;

    try {
        const dirty = await hasUnsavedChanges(currentOpenCard);

        if (!dirty) {
            return true;
        }

        const saveBtn = currentOpenCard.querySelector(".inline-save-btn");
        const ok = confirm("저장하지 않은 메모 또는 사진이 있습니다.\n로컬에 저장 후 이동할까요?");

        if (!ok) {
            return false;
        }

        if (saveBtn) {
            isAutoSavingBeforeMove = true;
            blockPhotoPicker(2000);

            const saved = await saveInlineInspection(saveBtn, {
                silent: true,
                keepOpen: false
            });

            setTimeout(() => {
                isAutoSavingBeforeMove = false;
            }, 300);

            return !!saved;
        }

        return true;
    } catch (e) {
        console.error(e);
        alert("변경사항 확인 중 오류가 발생했습니다.");
        isAutoSavingBeforeMove = false;
        return false;
    }
}

async function toggleItemEditorByButton(button) {
    const targetCard = button.closest(".item-card");
    const currentOpenCard = getCurrentOpenItemCard();

    if (currentOpenCard && currentOpenCard === targetCard) {
        const editor = currentOpenCard.querySelector(".item-editor");
        if (editor) {
            editor.classList.remove("open");
        }
        return;
    }

    const ok = await confirmAndSaveIfNeeded();
    if (!ok) return;

    await openItemEditor(button);
}

async function getLocalDeleteSummaryByCategoryGroup(siteId, categoryGroup) {
    const allDraftResults = await OfflineDB.getAll("draft_results");
    const allDraftPhotos = await OfflineDB.getAll("draft_photos");

    const targetDrafts = allDraftResults.filter(d =>
        Number(d.siteId) === Number(siteId) &&
        d.categoryGroup === categoryGroup
    );

    const memoCount = targetDrafts.filter(d => (d.memo || "").trim() !== "").length;
    const draftKeys = targetDrafts.map(d => d.draftKey);

    const photoCount = allDraftPhotos.filter(p =>
        draftKeys.includes(p.draftKey) &&
        p.isDeleted !== true
    ).length;

    return {
        memoCount,
        photoCount
    };
}

async function getLocationDeleteSummary(siteId, categoryGroup, locationBox = null) {
    let serverMemoCount = 0;
    let serverPhotoCount = 0;

    if (locationBox) {
        const itemCards = Array.from(locationBox.querySelectorAll(".item-card"));

        for (const card of itemCards) {
            const saveBtn = card.querySelector(".inline-save-btn");
            if (!saveBtn) continue;

            const itemId = saveBtn.dataset.itemId;
            const group = saveBtn.dataset.categoryGroup;

            if (group !== categoryGroup) continue;

            try {
                const serverRes = await $.ajax({
                    type: "GET",
                    url: "/inspection/detail",
                    data: { siteId, itemId, categoryGroup: group }
                });

                if ((serverRes.memo || "").trim() !== "") {
                    serverMemoCount++;
                }

                serverPhotoCount += (serverRes.photos || []).length;
            } catch (e) {
                console.error("위치 삭제 전 서버 데이터 조회 실패", e);
            }
        }
    }

    const localSummary = await getLocalDeleteSummaryByCategoryGroup(siteId, categoryGroup);

    return {
        memoCount: serverMemoCount + localSummary.memoCount,
        photoCount: serverPhotoCount + localSummary.photoCount
    };
}


function setItemResultAndSave(button, value) {
    const itemCard = button.closest(".item-card");
    const editor = itemCard.querySelector(".item-editor");
    mergeSpecialVisibleMemo(editor);
    const memo = getMemoInputForSave(editor)?.value.trim() || "";

    const hasPhoto = Array.from(editor.querySelectorAll(".photo-slot"))
        .some(slot => {
            const savedPhotoId = slot.dataset.savedPhotoId;
            const input = slot.querySelector(".inline-photo-input");
            const hasNewFile = input && input.files && input.files.length > 0;
            const hasPreview = slot.classList.contains("filled");
            return !!savedPhotoId || hasNewFile || hasPreview;
        });

    if (memo !== "" || hasPhoto) {
        const ok = confirm("사진 or 메모가 있는 항목입니다. 삭제하시겠습니까?");
        if (!ok) return;

        resetInspectionState(itemCard, "해당사항없음");
        return;
    }

    itemCard.dataset.currentResult = "해당사항없음";

    const saveBtn = itemCard.querySelector(".inline-save-btn");
    if (saveBtn) {
        saveInlineInspection(saveBtn);
    }
}

function clearAllInlinePhotos(editor) {
    editor.querySelectorAll(".photo-slot").forEach(slot => {
        const input = slot.querySelector(".inline-photo-input");
        const img = slot.querySelector(".photo-slot-img");
        const text = slot.querySelector(".photo-slot-text");
        const delBtn = slot.querySelector(".photo-delete-btn");

        if (input) {
            input.value = "";
        }

        if (img) {
            img.src = "";
            img.style.display = "none";
        }

        if (text) {
            text.style.display = "block";
        }

        if (delBtn) {
            delBtn.style.display = "none";
        }

        slot.classList.add("empty");
        slot.classList.remove("filled");
        delete slot.dataset.savedPhotoId;
    });
}

function resetInspectionState(itemCard, targetResult) {
    const siteId = $("#siteId").val();
    const saveBtn = itemCard.querySelector(".inline-save-btn");
    const itemId = saveBtn.dataset.itemId;
    const categoryGroup = saveBtn.dataset.categoryGroup;
    const editor = itemCard.querySelector(".item-editor");

    $.ajax({
        type: "POST",
        url: "/inspection/reset",
        data: {
            siteId,
            itemId,
            categoryGroup,
            targetResult
        },
        success: function () {
            editor.querySelectorAll(".photo-slot").forEach(slot => {
                clearInlineSlot(slot);
            });

            editor.querySelectorAll(".inline-memo").forEach(memoInput => {
                memoInput.value = "";
                memoInput.disabled = false;
                memoInput.readOnly = false;
            });

            itemCard.dataset.currentResult = targetResult;

            const row = itemCard.querySelector(".item-row");
            if (row) {
                if (targetResult === "해당사항없음") {
                    row.classList.add("done");
                } else {
                    row.classList.remove("done");
                }
            }

            const locationBox = itemCard.closest(".location-box");
            if (locationBox) {
                updateLocationProgress(locationBox);
            }

            syncStatusButton(itemCard);
            recalcSiteProgress();
        },
        error: function (xhr) {
            console.error(xhr.responseText);
            alert(xhr.responseText || "상태 변경 실패");
        }
    });
}

function bindMemoAutoWrite(editor) {
    const memoInputs = editor.querySelectorAll(".inline-memo");
    if (!memoInputs.length) return;

    memoInputs.forEach(memoInput => {
        memoInput.addEventListener("input", function () {
            const itemCard = editor.closest(".item-card");
            const itemRow = itemCard.querySelector(".item-row");

            if (memoInput.value.trim() !== "") {
                itemCard.dataset.currentResult = "작성";
                itemRow?.classList.add("done");
            }

            syncStatusButton(itemCard);
        });
    });
}

function openCategoryEditModal() {
    const siteId = $("#siteId").val();

    $.ajax({
        type: "GET",
        url: "/site/category-edit-data",
        data: {siteId: siteId},
        dataType: "json",
        success: function (result) {
            let html = "";
            const selectedSet = new Set(result.selectedCategories || []);

            (result.allCategories || []).forEach(function (item) {
                const checked = selectedSet.has(item.category) ? "checked" : "";

                html += `
                    <label class="category-item">
                        <input type="checkbox" name="editCategoryItem" value="${item.category}" ${checked}>
                        <span>${item.category}</span>
                    </label>
                `;
            });

            $("#categoryEditBox").html(html);
            $("#categoryEditModal").show();
        },
        error: function (xhr) {
            console.error(xhr.responseText);
            alert("점검항목 목록을 불러오지 못했습니다.");
        }
    });
}

function closeCategoryEditModal() {
    $("#categoryEditModal").hide();
}

$(document).on("click", "#editCheckAllBtn", function () {
    $("input[name='editCategoryItem']").prop("checked", true);
});

$(document).on("click", "#editUncheckAllBtn", function () {
    $("input[name='editCategoryItem']").prop("checked", false);
});

function saveCategoryEdit() {
    const siteId = $("#siteId").val();
    const categories = [];

    $("input[name='editCategoryItem']:checked").each(function () {
        categories.push($(this).val());
    });

    if (categories.length === 0) {
        alert("최소 1개 이상 선택해주세요.");
        return;
    }

    $.ajax({
        type: "POST",
        url: "/site/category-edit",
        contentType: "application/json",
        data: JSON.stringify({
            siteId: siteId,
            categories: categories
        }),
        success: function () {
            alert("점검항목이 수정되었습니다.");
            location.reload();
        },
        error: function (xhr) {
            console.error(xhr.responseText);
            alert(xhr.responseText || "점검항목 수정 중 오류가 발생했습니다.");
        }
    });
}

async function syncOfflineData() {
    if (isSyncing) return;

    const siteId = Number($("#siteId").val());

    try {
        isSyncing = true;
        setSyncLoading(true, "동기화 준비 중입니다.");

        const allResults = await OfflineDB.getAll("draft_results");
        const allPhotos = await OfflineDB.getAll("draft_photos");

        const results = allResults.filter(x =>
            x.siteId === siteId && x.syncStatus === "pending"
        );

        const resultDraftKeys = new Set(results.map(r => r.draftKey));

        const orphanPhotos = allPhotos.filter(x => {
            if (x.syncStatus !== "pending") return false;
            if (x.isDeleted === true) return false;
            if (!x.draftKey) return false;

            const parts = x.draftKey.split("_");
            const photoSiteId = Number(parts[0]);

            if (photoSiteId !== siteId) return false;

            return !resultDraftKeys.has(x.draftKey);
        });

        const orphanDraftKeySet = new Set();

        for (const photo of orphanPhotos) {
            if (orphanDraftKeySet.has(photo.draftKey)) {
                continue;
            }

            orphanDraftKeySet.add(photo.draftKey);

            const parts = photo.draftKey.split("_");

            if (parts.length < 3) {
                throw new Error(`잘못된 사진 draftKey입니다: ${photo.draftKey}`);
            }

            const photoSiteId = Number(parts[0]);
            const itemId = Number(parts[1]);
            const categoryGroup = parts.slice(2).join("_");

            const autoDraft = {
                draftKey: photo.draftKey,
                siteId: photoSiteId,
                itemId: itemId,
                categoryGroup: categoryGroup,
                result: "작성",
                memo: "",
                updatedAt: new Date().toISOString(),
                syncStatus: "pending"
            };

            await OfflineDB.putAndVerify("draft_results", autoDraft, "draftKey");

            results.push(autoDraft);
            resultDraftKeys.add(photo.draftKey);
        }

        const uploadPhotos = allPhotos.filter(x => {
            if (x.syncStatus !== "pending") return false;
            if (x.isDeleted === true) return false;
            return !!x.draftKey && resultDraftKeys.has(x.draftKey);
        });

        const deletePhotos = allPhotos.filter(x => {
            if (x.syncStatus !== "pending") return false;
            if (x.isDeleted !== true) return false;
            return !!x.serverPhotoId;
        });

        const totalSteps = results.length + uploadPhotos.length + deletePhotos.length;

        if (results.length === 0 && uploadPhotos.length === 0 && deletePhotos.length === 0) {
            updateSyncProgress(0, 0, "동기화할 데이터가 없습니다.");
            finishSyncStatus(true, "동기화할 데이터가 없습니다. 완료 버튼을 눌러 닫아주세요.");
            return;
        }

        let doneSteps = 0;

        updateSyncProgress(doneSteps, totalSteps, "점검 결과를 서버에 저장하는 중입니다.");

        const res = await fetch(`/sync/site/${siteId}`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                results,
                locations: []
            })
        });

        if (res.redirected || res.url.includes("/login")) {
            throw new Error("로그인이 만료되었습니다. 다시 로그인 후 동기화해주세요.");
        }

        if (!res.ok) {
            const msg = await res.text().catch(() => "");
            throw new Error(`결과 동기화 실패: ${msg || res.status}`);
        }

        const data = await res.json();
        const resultIdMap = data.resultIdMap || {};

        doneSteps += results.length;
        updateSyncProgress(doneSteps, totalSteps, "점검 결과 동기화 완료");

        for (const photo of deletePhotos) {
            const deleteRes = await fetch("/inspection/photo/delete", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: new URLSearchParams({
                    photoId: photo.serverPhotoId
                })
            });

            if (deleteRes.redirected || deleteRes.url.includes("/login")) {
                throw new Error("로그인이 만료되었습니다. 다시 로그인 후 동기화해주세요.");
            }

            if (!deleteRes.ok) {
                const msg = await deleteRes.text().catch(() => "");
                throw new Error(`기존 사진 삭제 실패: ${msg || deleteRes.status}`);
            }

            await OfflineDB.deleteByKey("draft_photos", photo.photoKey);

            doneSteps++;
            updateSyncProgress(doneSteps, totalSteps, "기존 사진 삭제 중.");
        }

        for (const photo of uploadPhotos) {
            const resultId = resultIdMap[photo.draftKey];

            if (!resultId) {
                throw new Error(`사진 업로드 대상 resultId 없음: ${photo.draftKey}`);
            }

            const formData = new FormData();
            formData.append("resultId", resultId);
            formData.append("photo", photo.fileBlob, photo.fileName || "photo.jpg");

            const uploadRes = await fetch("/sync/photo/upload", {
                method: "POST",
                body: formData
            });

            if (uploadRes.redirected || uploadRes.url.includes("/login")) {
                throw new Error("로그인이 만료되었습니다. 다시 로그인 후 동기화해주세요.");
            }

            if (!uploadRes.ok) {
                const msg = await uploadRes.text().catch(() => "");
                throw new Error(`사진 업로드 실패: ${msg || uploadRes.status}`);
            }

            await OfflineDB.deleteByKey("draft_photos", photo.photoKey);

            doneSteps++;
            updateSyncProgress(doneSteps, totalSteps, "사진 업로드 중.");
        }

        for (const r of results) {
            await OfflineDB.deleteByKey("draft_results", r.draftKey);
        }

        await refreshVisibleItemStates();
        await updatePendingSyncBadge();

        finishSyncStatus(true, "동기화가 완료되었습니다. 완료 버튼을 눌러 닫아주세요.");
    } catch (e) {
        console.error(e);
        finishSyncStatus(false, e.message || "동기화 중 문제가 발생했습니다.");
    } finally {
        isSyncing = false;
    }
}

function generateUUID() {
    if (window.crypto && crypto.randomUUID) {
        return crypto.randomUUID();
    }

    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

async function openDownloadModal() {
    const siteId = $("#siteId").val();

    openZipLoading();
    setZipProgress(5, "다운로드 항목 불러오는 중...");

    try {
        const allDrafts = await OfflineDB.getAll("draft_results");
        const allPhotos = await OfflineDB.getAll("draft_photos");

        let html = "";
        const categoryBoxes = Array.from(document.querySelectorAll(".category-box"));
        let processed = 0;
        const total = Math.max(categoryBoxes.length, 1);

        for (const categoryBox of categoryBoxes) {
            const titleSpan = categoryBox.querySelector(".category-title span");
            if (!titleSpan) {
                processed++;
                setZipProgress(Math.min(90, Math.round((processed / total) * 90)), "다운로드 항목 불러오는 중...");
                continue;
            }

            const rawTitle = titleSpan.textContent.trim();
            const categoryLabel = rawTitle.replace(/\s*\(\d+\)\s*$/, "").trim();

            const itemCards = Array.from(categoryBox.querySelectorAll(".item-card"));
            if (itemCards.length === 0) {
                processed++;
                setZipProgress(Math.min(90, Math.round((processed / total) * 90)), "다운로드 항목 불러오는 중...");
                continue;
            }

            let hasValueInCategory = false;
            const selectedPairs = [];

            for (const card of itemCards) {
                const saveBtn = card.querySelector(".inline-save-btn");
                if (!saveBtn) continue;

                const itemId = saveBtn.dataset.itemId;
                const categoryGroup = saveBtn.dataset.categoryGroup;
                const draftKey = makeDraftKey(siteId, itemId, categoryGroup);

                const localDraft = allDrafts.find(x => x.draftKey === draftKey);
                const localPhotos = allPhotos.filter(x => x.draftKey === draftKey && x.isDeleted !== true);

                let serverRes = null;
                try {
                    serverRes = await $.ajax({
                        type: "GET",
                        url: "/inspection/detail",
                        data: { siteId, itemId, categoryGroup }
                    });
                } catch (e) {
                    console.error("다운로드 항목 조회 실패", e);
                    continue;
                }

                const deletedServerPhotoIds = allPhotos
                    .filter(photo => photo.isDeleted === true && photo.serverPhotoId)
                    .map(photo => Number(photo.serverPhotoId));

                const serverPhotos = (serverRes.photos || [])
                    .filter(photo => !deletedServerPhotoIds.includes(Number(photo.id)));

                const finalMemo = localDraft ? (localDraft.memo || "") : (serverRes.memo || "");
                const finalResult = localDraft ? (localDraft.result || "미작성") : (serverRes.result || "미작성");
                const photoCount = serverPhotos.length + localPhotos.length;

                const hasValue =
                    finalResult === "해당사항없음" ||
                    (finalMemo && finalMemo.trim() !== "") ||
                    photoCount > 0;

                if (hasValue) {
                    hasValueInCategory = true;
                    selectedPairs.push(`${itemId}::${categoryGroup}`);
                }
            }

            if (hasValueInCategory && selectedPairs.length > 0) {
                html += `
                    <label class="category-item" style="display:block; margin-bottom:8px;">
                        <input type="checkbox"
                               name="downloadCategory"
                               value="${categoryLabel}"
                               data-pairs="${selectedPairs.join('|')}"
                               checked>
                        <span>${categoryLabel}</span>
                    </label>
                `;
            }

            processed++;
            setZipProgress(Math.min(90, Math.round((processed / total) * 90)), "다운로드 항목 불러오는 중...");
        }

        if (!html) {
            html = `<div style="padding:8px 0;">다운로드할 값이 있는 점검항목이 없습니다.</div>`;
        }

        document.getElementById("downloadItemBox").innerHTML = html;
        closeZipLoading();
        document.getElementById("downloadModal").style.display = "flex";

    } catch (e) {
        console.error(e);
        closeZipLoading();
        alert("다운로드 항목 조회 중 오류가 발생했습니다.");
    }
}

function closeDownloadModal() {
    document.getElementById("downloadModal").style.display = "none";
}

$(document).on("click", "#downloadCheckAllBtn", function () {
    $("input[name='downloadCategory']").prop("checked", true);
});

$(document).on("click", "#downloadUncheckAllBtn", function () {
    $("input[name='downloadCategory']").prop("checked", false);
});

let zipStatusTimer = null;

function startZipStatusAnimation() {
    const messages = [
        "ZIP 파일 생성 중.",
        "ZIP 파일 생성 중..",
        "ZIP 파일 생성 중...",
        "사진 묶는 중.",
        "사진 묶는 중..",
        "사진 묶는 중...",
        "압축 파일 준비 중.",
        "압축 파일 준비 중..",
        "압축 파일 준비 중..."
    ];

    let i = 0;
    zipStatusTimer = setInterval(() => {
        const el = document.getElementById("zipStatusText");
        if (!el) return;
        el.innerText = messages[i % messages.length];
        i++;
    }, 500);
}

function stopZipStatusAnimation() {
    if (zipStatusTimer) {
        clearInterval(zipStatusTimer);
        zipStatusTimer = null;
    }
}

async function submitSelectedDownload() {
    const siteId = $("#siteId").val();
    const checked = Array.from(document.querySelectorAll("input[name='downloadCategory']:checked"));

    if (checked.length === 0) {
        alert("최소 1개 이상 선택해주세요.");
        return;
    }

    const addedKeys = new Set();
    const itemIds = [];
    const categoryGroups = [];

    checked.forEach(chk => {
        const pairs = (chk.dataset.pairs || "").split("|").filter(Boolean);

        pairs.forEach(pair => {
            const [itemId, categoryGroup] = pair.split("::");
            if (!itemId || !categoryGroup) return;

            const key = `${itemId}::${categoryGroup}`;
            if (addedKeys.has(key)) return;

            addedKeys.add(key);
            itemIds.push(Number(itemId));
            categoryGroups.push(categoryGroup);
        });
    });

    if (itemIds.length === 0) {
        alert("다운로드할 항목이 없습니다.");
        return;
    }

    let progressTimer = null;

    closeDownloadModal();          // 추가
    openZipLoading();
    setZipProgress(10, "ZIP 파일 생성 중...");
    startZipStatusAnimation();

    try {
        progressTimer = startZipFakeProgress();

        const response = await fetch(`/site/${siteId}/download-selected`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({
                itemIds,
                categoryGroups
            })
        });

        if (!response.ok) {
            const msg = await response.text().catch(() => "");
            throw new Error(msg || "선택 다운로드 실패");
        }

        const blob = await response.blob();

        finishZipProgress("다운로드 시작 중...");

        const disposition = response.headers.get("Content-Disposition") || "";
        const fileName = getFileNameFromDisposition(disposition) || `site_${siteId}_selected.zip`;

        const url = window.URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);

        setZipProgress(100, "다운로드 완료");
        document.getElementById("zipCloseBtn").disabled = false;

        setTimeout(() => {
            closeZipLoading();
        }, 800);

    } catch (e) {
        console.error(e);
        setZipProgress(0, "선택 다운로드 실패");
        document.getElementById("zipCloseBtn").disabled = false;
        alert(e.message || "선택 다운로드 중 오류가 발생했습니다.");
    } finally {
        stopZipFakeProgress(progressTimer);
        stopZipStatusAnimation();
    }
}

async function startExcelDownload(siteId) {
    if (window.__excelDownloading === true) return;
    window.__excelDownloading = true;

    openExcelLoading();

    let progressTimer = null;

    try {
        progressTimer = startExcelFakeProgress();

        const response = await fetch(`/site/${siteId}/excel`, {
            method: "GET"
        });

        if (!response.ok) {
            throw new Error("엑셀 다운로드 실패");
        }

        const blob = await response.blob();

        finishExcelProgress("다운로드 시작 중...");

        const disposition = response.headers.get("Content-Disposition") || "";
        const fileName = getFileNameFromDisposition(disposition) || `site_${siteId}.xlsx`;

        const url = window.URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        a.remove();

        window.URL.revokeObjectURL(url);

        setExcelProgress(100, "다운로드 완료");
        document.getElementById("excelCloseBtn").disabled = false;

        setTimeout(() => {
            closeExcelLoading();
        }, 800);

    } catch (e) {
        console.error(e);
        stopExcelFakeProgress(progressTimer);
        setExcelProgress(0, "엑셀 다운로드 실패");
        document.getElementById("excelCloseBtn").disabled = false;
        alert("엑셀 다운로드 중 오류가 발생했습니다.");
    } finally {
        stopExcelFakeProgress(progressTimer);
        window.__excelDownloading = false;
    }
}

function startExcelFakeProgress() {
    let progress = 0;

    const timer = setInterval(() => {
        if (progress < 30) {
            progress += 4;
        } else if (progress < 60) {
            progress += 2;
        } else if (progress < 85) {
            progress += 1;
        } else if (progress < 90) {
            progress += 0.3;
        }

        if (progress > 90) {
            progress = 90;
        }

        setExcelProgress(progress, "엑셀 변환 중...");
    }, 200);

    return timer;
}

function openExcelLoading() {
    document.getElementById("excelLoadingModal").style.display = "flex";
    document.getElementById("excelProgressFill").style.width = "0%";
    document.getElementById("excelProgressText").innerText = "0%";
    document.getElementById("excelStatusText").innerText = "엑셀 변환 중...";
    document.getElementById("excelCloseBtn").disabled = true;
}

function closeExcelLoading() {
    document.getElementById("excelLoadingModal").style.display = "none";
}

function setExcelProgress(percent, statusText) {
    const safePercent = Math.max(0, Math.min(100, Math.floor(percent)));
    document.getElementById("excelProgressFill").style.width = `${safePercent}%`;
    document.getElementById("excelProgressText").innerText = `${safePercent}%`;

    if (statusText) {
        document.getElementById("excelStatusText").innerText = statusText;
    }
}

function stopExcelFakeProgress(timer) {
    if (timer) {
        clearInterval(timer);
    }
}

function finishExcelProgress(statusText = "다운로드 준비 완료") {
    setExcelProgress(100, statusText);
}

function getFileNameFromDisposition(disposition) {
    if (!disposition) return null;

    const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/i);
    if (utf8Match && utf8Match[1]) {
        return decodeURIComponent(utf8Match[1]);
    }

    const asciiMatch = disposition.match(/filename="?([^"]+)"?/i);
    if (asciiMatch && asciiMatch[1]) {
        return asciiMatch[1];
    }

    return null;
}

async function startZipDownload(siteId) {
    if (window.__zipDownloading === true) return;
    window.__zipDownloading = true;

    openZipLoading();

    let progressTimer = null;

    try {
        progressTimer = startZipFakeProgress();

        const response = await fetch(`/site/${siteId}/download`, {
            method: "GET"
        });

        if (!response.ok) {
            throw new Error("ZIP 다운로드 실패");
        }

        const blob = await response.blob();

        finishZipProgress("ZIP 생성 완료. 다운로드 시작 중...");

        const disposition = response.headers.get("Content-Disposition") || "";
        const fileName = getFileNameFromDisposition(disposition) || `site_${siteId}.zip`;

        const url = window.URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        a.remove();

        window.URL.revokeObjectURL(url);

        setZipProgress(100, "다운로드 완료");
        document.getElementById("zipCloseBtn").disabled = false;

        setTimeout(() => {
            closeZipLoading();
        }, 800);

    } catch (e) {
        console.error(e);
        stopZipFakeProgress(progressTimer);
        setZipProgress(0, "ZIP 다운로드 실패");
        document.getElementById("zipCloseBtn").disabled = false;
        alert("전체 다운로드 중 오류가 발생했습니다.");
    } finally {
        stopZipFakeProgress(progressTimer);
        window.__zipDownloading = false;
    }
}

function openZipLoading() {
    document.getElementById("zipLoadingModal").style.display = "flex";
    document.getElementById("zipProgressFill").style.width = "0%";
    document.getElementById("zipProgressText").innerText = "0%";
    document.getElementById("zipStatusText").innerText = "ZIP 파일 생성 중...";
    document.getElementById("zipCloseBtn").disabled = true;
}

function closeZipLoading() {
    document.getElementById("zipLoadingModal").style.display = "none";
}

function setZipProgress(percent, statusText) {
    const safePercent = Math.max(0, Math.min(100, Math.floor(percent)));
    document.getElementById("zipProgressFill").style.width = `${safePercent}%`;
    document.getElementById("zipProgressText").innerText = `${safePercent}%`;

    if (statusText) {
        document.getElementById("zipStatusText").innerText = statusText;
    }
}

function startZipFakeProgress() {
    let progress = 0;

    const timer = setInterval(() => {
        if (progress < 30) {
            progress += 4;
        } else if (progress < 60) {
            progress += 2;
        } else if (progress < 85) {
            progress += 1;
        } else if (progress < 92) {
            progress += 0.3;
        }

        if (progress > 92) {
            progress = 92;
        }

        setZipProgress(progress, "ZIP 파일 생성 중...");
    }, 200);

    return timer;
}

function stopZipFakeProgress(timer) {
    if (timer) {
        clearInterval(timer);
    }
}

function finishZipProgress(statusText = "다운로드 준비 완료") {
    setZipProgress(100, statusText);
}


let currentPreviewSlot = null;

function openPhotoPreview(slot) {
    const img = slot.querySelector(".photo-slot-img");
    const modal = document.getElementById("photoPreviewModal");
    const previewImg = document.getElementById("photoPreviewImg");

    if (!img || !img.src) return;
    if (!modal || !previewImg) {
        console.error("photoPreviewModal 또는 photoPreviewImg가 없습니다.");
        return;
    }

    currentPreviewSlot = slot;
    previewImg.src = img.src;
    modal.style.display = "flex";
}

function closePhotoPreview() {
    const modal = document.getElementById("photoPreviewModal");
    const previewImg = document.getElementById("photoPreviewImg");

    if (modal) modal.style.display = "none";
    if (previewImg) previewImg.src = "";

    currentPreviewSlot = null;
}

function replacePreviewPhoto(event) {
    if (event) {
        event.preventDefault();
        event.stopPropagation();
    }

    if (isPhotoPickerBlocked()) return;
    if (!currentPreviewSlot) return;

    const input = currentPreviewSlot.querySelector(".inline-gallery-input");
    if (input) {
        input.removeAttribute("capture");
        input.click();
    }

    closePhotoPreview();
}


function handlePhotoSlotClick(slot, event) {
    if (event) {
        event.preventDefault();
        event.stopPropagation();
    }

    if (isPhotoPickerBlocked()) return;

    if (slot.classList.contains("filled")) {
        openPhotoPreview(slot);
        return;
    }

    const cameraInput = slot.querySelector(".inline-photo-input");
    if (cameraInput) {
        cameraInput.setAttribute("capture", "environment");
        cameraInput.click();
    }
}



function createPhotoSlot() {
    const div = document.createElement("div");
    div.className = "photo-slot empty";

    div.innerHTML = `
        <span class="photo-slot-text">+ 현장사진</span>
        <img class="photo-slot-img" style="display:none;">

        <span class="photo-save-badge">미저장</span>

        <input type="file"
               class="inline-photo-input"
               accept="image/*"
               capture="environment"
               style="display:none;">

        <input type="file"
               class="inline-gallery-input"
               accept="image/jpeg,image/png,image/webp"
               style="display:none;">

        <button type="button"
                class="photo-delete-btn"
                style="display:none;"
                onclick="removeInlinePhoto(event, this)">×</button>
    `;

    div.onclick = function (e) {
        handlePhotoSlotClick(div, e);
    };

    return div;
}

async function attachGalleryPhotoToSlot(input) {
    const file = input.files && input.files[0];
    if (!file) return;

    const editor = input.closest(".item-editor");
    if (!editor) return;

    let slot = editor.querySelector(".photo-slot.empty");

    if (!slot) {
        const grid = editor.querySelector(".editor-photo-grid");
        slot = createPhotoSlot();
        grid.appendChild(slot);
        bindInlinePhotoSlot(slot);
    }

    const saveBtn = editor.querySelector(".inline-save-btn");

    if (!saveBtn) {
        alert("저장 대상 항목을 찾지 못했습니다.");
        input.value = "";
        return;
    }

    input.value = "";

    await savePhotoFileToSlot(file, slot, saveBtn);
}

async function savePhotoFileToSlot(file, slot, saveBtn) {
    const img = slot.querySelector(".photo-slot-img");
    const text = slot.querySelector(".photo-slot-text");
    const delBtn = slot.querySelector(".photo-delete-btn");

    const siteId = $("#siteId").val();
    const itemId = saveBtn.dataset.itemId;
    const categoryGroup = saveBtn.dataset.categoryGroup;
    const draftKey = makeDraftKey(siteId, itemId, categoryGroup);

    try {
        markPhotoSlotPending(slot);

        const savedPhotoId = slot.dataset.savedPhotoId;

        if (savedPhotoId) {
            await OfflineDB.putAndVerify("draft_photos", {
                photoKey: `delete_${savedPhotoId}`,
                serverPhotoId: Number(savedPhotoId),
                isDeleted: true,
                syncStatus: "pending",
                createdAt: new Date().toISOString()
            }, "photoKey");
        }

        if (slot.dataset.localPhotoKey) {
            await OfflineDB.deleteByKey("draft_photos", slot.dataset.localPhotoKey);
            delete slot.dataset.localPhotoKey;
        }

        const photoKey = generateUUID();

        await OfflineDB.putAndVerify("draft_photos", {
            photoKey,
            draftKey,
            fileBlob: file,
            fileName: file.name || "photo.jpg",
            isDeleted: false,
            syncStatus: "pending",
            createdAt: new Date().toISOString()
        }, "photoKey");

        slot.dataset.localPhotoKey = photoKey;
        delete slot.dataset.savedPhotoId;

        const previewUrl = URL.createObjectURL(file);

        if (img.dataset.objectUrl) {
            URL.revokeObjectURL(img.dataset.objectUrl);
        }

        img.dataset.objectUrl = previewUrl;
        img.src = previewUrl;
        img.style.display = "block";

        if (text) text.style.display = "none";
        if (delBtn) delBtn.style.display = "flex";

        slot.classList.remove("empty");
        slot.classList.add("filled");

        const saved = await saveInlineInspection(saveBtn, {
            silent: true,
            keepOpen: true
        });

        if (!saved) {
            throw new Error("점검 결과 로컬 저장 실패");
        }

        markPhotoSlotSaved(slot);

        const itemCard = saveBtn.closest(".item-card");
        if (itemCard) {
            itemCard.dataset.currentResult = "작성";
            itemCard.querySelector(".item-row")?.classList.add("done");
            syncStatusButton(itemCard);

            const locationBox = itemCard.closest(".location-box");
            if (locationBox) updateLocationProgress(locationBox);
        }

        recalcSiteProgress();
        await updatePendingSyncBadge();

    } catch (e) {
        console.error(e);
        markPhotoSlotFailed(slot);
        alert("사진 로컬 저장 실패");
    }
}

function saveEfficiencySheet(button) {
    const editor = button.closest(".item-editor");
    const memo = editor.querySelector(".efficiency-json-memo");
    const inputs = editor.querySelectorAll(".eff-input");

    const rows = {};
    const summary = {};

    inputs.forEach(input => {
        const row = input.dataset.row;
        const field = input.dataset.field;
        const summaryField = input.dataset.summary;

        if (row && field) {
            if (!rows[row]) rows[row] = {};
            rows[row][field] = input.value || "";
        }

        if (summaryField) {
            summary[summaryField] = input.value || "";
        }
    });

    memo.value = JSON.stringify({
        type: "efficiencySheet",
        rows,
        summary
    });

    mergeSpecialVisibleMemo(editor);
    saveInlineInspection(button);
}

async function renameLocation(button) {
    const ok = await confirmAndSaveIfNeeded();
    if (!ok) return;

    const siteId = $("#siteId").val();
    const oldCategoryGroup = button.dataset.categoryGroup;
    const oldLocationName = button.dataset.locationName || "";

    const newLocationName = prompt("새 위치명을 입력하세요.", oldLocationName);

    if (newLocationName === null) return;

    const trimmedName = newLocationName.trim();

    if (!trimmedName) {
        alert("위치명을 입력해주세요.");
        return;
    }

    if (trimmedName.includes("_")) {
        alert("위치명에는 _ 문자를 사용할 수 없습니다.");
        return;
    }

    if (trimmedName === oldLocationName) {
        return;
    }

    if (button.dataset.renaming === "true") return;

    const categoryBox = button.closest(".category-box");
    const templateCategory = oldCategoryGroup.split("_")[0];

    const originalText = button.textContent;
    button.dataset.renaming = "true";
    button.disabled = true;
    button.textContent = "수정중...";

    $.ajax({
        type: "POST",
        url: "/category/rename",
        data: {
            siteId,
            oldCategoryGroup,
            newLocationName: trimmedName
        },
        success: async function (res) {
            try {
                const newCategoryGroup = res.newGroupName;

                await updateLocalDraftCategoryGroup(
                    Number(siteId),
                    oldCategoryGroup,
                    newCategoryGroup
                );

                $.ajax({
                    type: "GET",
                    url: "/category/location-list",
                    data: {
                        siteId,
                        categoryName: templateCategory
                    },
                    success: async function (html) {
                        const oldList = categoryBox.querySelector(".location-list");
                        const categoryInner = categoryBox.querySelector(".category-inner");
                        const addBox = categoryInner.querySelector(".location-add-box");

                        if (oldList) {
                            oldList.outerHTML = html;
                        } else {
                            addBox.insertAdjacentHTML("afterend", html);
                        }

                        categoryBox.querySelectorAll(".item-editor").forEach(bindMemoAutoWrite);
                        categoryBox.querySelectorAll(".photo-slot").forEach(bindInlinePhotoSlot);
                        categoryBox.querySelectorAll(".item-card").forEach(syncStatusButton);

                        const newLocationBox = Array.from(categoryBox.querySelectorAll(".location-box"))
                            .find(box => {
                                const title = box.querySelector(".location-title-main")?.textContent?.trim();
                                return title === trimmedName;
                            });

                        if (newLocationBox) {
                            newLocationBox.querySelector(".item-list")?.classList.add("open");

                            const itemCards = Array.from(newLocationBox.querySelectorAll(".item-card"));

                            for (const card of itemCards) {
                                const saveBtn = card.querySelector(".inline-save-btn");
                                const editor = card.querySelector(".item-editor");
                                if (!saveBtn || !editor) continue;

                                const fakeBtn = {
                                    dataset: {
                                        itemId: saveBtn.dataset.itemId,
                                        categoryGroup: saveBtn.dataset.categoryGroup
                                    },
                                    closest: () => card
                                };

                                await loadInlineInspectionData(fakeBtn, editor);
                            }

                            updateLocationProgress(newLocationBox);
                        }

                        await refreshVisibleItemStates();
                        await updatePendingSyncBadge();

                        updateCategoryLocationCount(categoryBox);
                        recalcSiteProgress();

                        const inner = categoryBox.querySelector(".category-inner");
                        if (inner) {
                            inner.classList.add("open");
                        }

                        alert("위치명이 수정되었습니다.");
                    },
                    error: function (xhr) {
                        console.error(xhr.responseText);
                        alert("위치 목록 갱신 실패");
                    }
                });

            } catch (e) {
                console.error(e);
                alert("로컬 데이터 위치명 반영 중 오류가 발생했습니다.");
            }
        },
        error: function (xhr) {
            console.error(xhr.responseText);
            alert(xhr.responseText || "위치명 수정 실패");
        },
        complete: function () {
            button.dataset.renaming = "false";
            button.disabled = false;
            button.textContent = originalText;
        }
    });
}

async function updateLocalDraftCategoryGroup(siteId, oldCategoryGroup, newCategoryGroup) {
    if (!window.OfflineDB) return;

    const allResults = await OfflineDB.getAll("draft_results");
    const allPhotos = await OfflineDB.getAll("draft_photos");

    const changedKeyMap = new Map();

    const targetResults = allResults.filter(r =>
        Number(r.siteId) === Number(siteId) &&
        r.categoryGroup === oldCategoryGroup
    );

    for (const draft of targetResults) {
        const oldDraftKey = draft.draftKey;
        const newDraftKey = makeDraftKey(siteId, draft.itemId, newCategoryGroup);

        changedKeyMap.set(oldDraftKey, newDraftKey);

        await OfflineDB.deleteByKey("draft_results", oldDraftKey);

        draft.categoryGroup = newCategoryGroup;
        draft.draftKey = newDraftKey;
        draft.updatedAt = new Date().toISOString();
        draft.syncStatus = "pending";

        await OfflineDB.put("draft_results", draft);
    }

    for (const photo of allPhotos) {
        if (!photo.draftKey) continue;

        const newDraftKey = changedKeyMap.get(photo.draftKey);
        if (!newDraftKey) continue;

        await OfflineDB.deleteByKey("draft_photos", photo.photoKey);

        photo.draftKey = newDraftKey;
        photo.syncStatus = "pending";

        await OfflineDB.put("draft_photos", photo);
    }

    await updatePendingSyncBadge();
}


function restoreAirflowSheet(editor, memoValue) {
    try {
        const data = JSON.parse(memoValue);
        if (!data || data.type !== "airflowSheet") return;

        const jsonMemo = editor.querySelector(".airflow-json-memo");
        if (jsonMemo) jsonMemo.value = memoValue;

        const visibleMemo = editor.querySelector(".special-visible-memo");
        if (visibleMemo) visibleMemo.value = data.userMemo || "";

        const tables = data.tables || {};

        editor.querySelectorAll(".airflow-input").forEach(input => {
            const table = input.dataset.table;
            const row = input.dataset.row;
            const col = input.dataset.col;
            input.value = tables?.[table]?.rows?.[row]?.[col] || "";
        });

        editor.querySelectorAll(".airflow-summary-input").forEach(input => {
            const table = input.dataset.table;
            const summary = input.dataset.summary;
            input.value = tables?.[table]?.summary?.[summary] || "";
        });
    } catch (e) {
        console.error("풍량측정표 복원 실패", e);
    }
}

function restoreEfficiencySheet(editor, memoValue) {
    try {
        const data = JSON.parse(memoValue);
        if (!data || data.type !== "efficiencySheet") return;

        const jsonMemo = editor.querySelector(".efficiency-json-memo");
        if (jsonMemo) jsonMemo.value = memoValue;

        const visibleMemo = editor.querySelector(".special-visible-memo");
        if (visibleMemo) visibleMemo.value = data.userMemo || "";

        const rows = data.rows || {};
        const summary = data.summary || {};

        editor.querySelectorAll(".eff-input").forEach(input => {
            const row = input.dataset.row;
            const field = input.dataset.field;
            const summaryField = input.dataset.summary;

            if (row && field) {
                input.value = rows?.[row]?.[field] || "";
            }

            if (summaryField) {
                input.value = summary?.[summaryField] || "";
            }
        });
    } catch (e) {
        console.error("효율시트 복원 실패", e);
    }
}

function restoreFanControlSheet(editor, memoValue) {
    try {
        const data = JSON.parse(memoValue);
        if (!data || data.type !== "fanControlSheet") return;

        const jsonMemo = editor.querySelector(".fan-control-json-memo");
        if (jsonMemo) jsonMemo.value = memoValue;

        const visibleMemo = editor.querySelector(".special-visible-memo");
        if (visibleMemo) visibleMemo.value = data.userMemo || "";

        const rows = data.rows || {};

        editor.querySelectorAll(".fan-control-input").forEach(input => {
            const row = input.dataset.row;
            const field = input.dataset.field;

            if (row && field) {
                input.value = rows?.[row]?.[field] || "";
            }
        });
    } catch (e) {
        console.error("풍량조절표 복원 실패", e);
    }
}

async function saveAirflowSheet(button) {
    const editor = button.closest(".item-editor");
    const memo = editor.querySelector(".airflow-json-memo");

    const data = {
        type: "airflowSheet",
        tables: {}
    };

    editor.querySelectorAll(".airflow-input").forEach(input => {
        const table = input.dataset.table;
        const row = input.dataset.row;
        const col = input.dataset.col;
        const value = input.value || "";

        if (!data.tables[table]) {
            data.tables[table] = {
                rows: {},
                summary: {}
            };
        }

        if (!data.tables[table].rows[row]) {
            data.tables[table].rows[row] = {};
        }

        data.tables[table].rows[row][col] = value;
    });

    editor.querySelectorAll(".airflow-summary-input").forEach(input => {
        const table = input.dataset.table;
        const key = input.dataset.summary;
        const value = input.value || "";

        if (!data.tables[table]) {
            data.tables[table] = {
                rows: {},
                summary: {}
            };
        }

        data.tables[table].summary[key] = value;
    });

    memo.value = JSON.stringify(data);

    mergeSpecialVisibleMemo(editor);
    await saveInlineInspection(button);
}

async function saveFanControlSheet(button) {
    const editor = button.closest(".item-editor");
    const memo = editor.querySelector(".fan-control-json-memo");

    const data = {
        type: "fanControlSheet",
        rows: {}
    };

    editor.querySelectorAll(".fan-control-input").forEach(input => {
        const row = input.dataset.row;
        const field = input.dataset.field;
        const value = input.value || "";

        if (!data.rows[row]) {
            data.rows[row] = {};
        }

        data.rows[row][field] = value;
    });

    memo.value = JSON.stringify(data);

    mergeSpecialVisibleMemo(editor);
    await saveInlineInspection(button);
}

$(document).on("input", ".eff-calc-input, .eff-input[data-field='energy']", function () {
    calculateEfficiencySheet($(this).closest(".efficiency-sheet-wrap"));
});

async function saveLocalInspectionDraft(data) {
    const draftKey = makeDraftKey(data.siteId, data.itemId, data.categoryGroup);

    await OfflineDB.putAndVerify("draft_results", {
        draftKey,
        siteId: Number(data.siteId),
        itemId: Number(data.itemId),
        categoryGroup: data.categoryGroup,
        result: data.result || "작성",
        memo: data.memo || "",
        updatedAt: new Date().toISOString(),
        syncStatus: "pending"
    }, "draftKey");

    await updatePendingSyncBadge();
}

function markItemSaved(btn) {
    const itemCard = btn.closest(".item-card");
    if (!itemCard) return;

    const itemRow = itemCard.querySelector(".item-row");

    itemCard.dataset.currentResult = "작성";

    if (itemRow) {
        itemRow.classList.add("done");
    }

    syncStatusButton(itemCard);

    const locationBox = itemCard.closest(".location-box");
    if (locationBox) {
        updateLocationProgress(locationBox);
    }

    recalcSiteProgress();
}

function toNumber(value) {
    const n = parseFloat(value);
    return isNaN(n) ? null : n;
}

function setValue($wrap, selector, value, fixed) {
    if (value === null || isNaN(value)) {
        $wrap.find(selector).val("");
        return;
    }

    if (fixed !== undefined) {
        $wrap.find(selector).val(Number(value).toFixed(fixed));
    } else {
        $wrap.find(selector).val(value);
    }
}

function calculateEfficiencySheet($wrap) {
    let highSum = 0;
    let highCount = 0;

    let lowSum = 0;
    let lowCount = 0;

    let flowSum = 0;
    let heatSum = 0;
    let energySum = 0;

    for (let i = 1; i <= 30; i++) {
        const high = toNumber($wrap.find(`.eff-input[data-row='${i}'][data-field='highTemp']`).val());
        const low = toNumber($wrap.find(`.eff-input[data-row='${i}'][data-field='lowTemp']`).val());
        const flow = toNumber($wrap.find(`.eff-input[data-row='${i}'][data-field='flow']`).val());
        const energy = toNumber($wrap.find(`.eff-input[data-row='${i}'][data-field='energy']`).val());

        if (high !== null) {
            highSum += high;
            highCount++;
        }

        if (low !== null) {
            lowSum += low;
            lowCount++;
        }

        if (flow !== null) {
            flowSum += flow;
        }

        if (energy !== null) {
            energySum += energy;
        }

        if (high !== null && low !== null && flow !== null) {
            const heat = (high - low) * flow * 60;
            $wrap.find(`.eff-input[data-row='${i}'][data-field='heat']`).val(heat.toFixed(2));
            heatSum += heat;
        } else {
            $wrap.find(`.eff-input[data-row='${i}'][data-field='heat']`).val("");
        }
    }

    const avgHigh = highCount > 0 ? highSum / highCount : null;
    const avgLow = lowCount > 0 ? lowSum / lowCount : null;

    setValue($wrap, ".eff-input[data-summary='avgHighTemp']", avgHigh, 1);
    setValue($wrap, ".eff-input[data-summary='avgLowTemp']", avgLow, 1);
    setValue($wrap, ".eff-input[data-summary='totalFlow']", flowSum, 2);
    setValue($wrap, ".eff-input[data-summary='totalHeat']", heatSum, 2);
    setValue($wrap, ".eff-input[data-summary='totalEnergy']", energySum, 2);
}

$(document).on("input", ".airflow-input, .airflow-summary-input", function () {
    calculateAirflowTable($(this).closest(".airflow-table-box"), $(this).data("table"));
});

function calculateAirflowTable($box, tableKey) {
    let speedSum = 0;
    let speedCount = 0;

    $box.find(`.airflow-input[data-table='${tableKey}']`).each(function () {
        const value = toNumber($(this).val());

        if (value !== null) {
            speedSum += value;
            speedCount++;
        }
    });

    const avgSpeed = speedCount > 0 ? speedSum / speedCount : null;

    const ductWidth = toNumber($box.find(`.airflow-summary-input[data-table='${tableKey}'][data-summary='ductWidth']`).val());
    const ductHeight = toNumber($box.find(`.airflow-summary-input[data-table='${tableKey}'][data-summary='ductHeight']`).val());

    let area = null;
    if (ductWidth !== null && ductHeight !== null) {
        area = ductWidth * ductHeight;
    }

    let measuredAirflow = null;
    if (avgSpeed !== null && area !== null) {
        measuredAirflow = avgSpeed * area * 3600;
    }

    setValue($box, `.airflow-summary-input[data-table='${tableKey}'][data-summary='avgSpeed']`, avgSpeed, 2);
    setValue($box, `.airflow-summary-input[data-table='${tableKey}'][data-summary='area']`, area, 2);
    setValue($box, `.airflow-summary-input[data-table='${tableKey}'][data-summary='measuredAirflow']`, measuredAirflow, 2);
}

function markPhotoSlotPending(slot) {
    const badge = slot.querySelector(".photo-save-badge");
    slot.classList.remove("local-saved");
    slot.classList.add("local-pending");

    if (badge) {
        badge.textContent = "저장중";
        badge.style.display = "inline-flex";
    }
}

function markPhotoSlotSaved(slot) {
    const badge = slot.querySelector(".photo-save-badge");
    slot.classList.remove("local-pending");
    slot.classList.add("local-saved");

    if (badge) {
        badge.textContent = "로컬저장";
        badge.style.display = "inline-flex";
    }
}

function markPhotoSlotFailed(slot) {
    const badge = slot.querySelector(".photo-save-badge");
    slot.classList.remove("local-saved");
    slot.classList.add("local-pending");

    if (badge) {
        badge.textContent = "저장실패";
        badge.style.display = "inline-flex";
    }
}

function getMemoInputForSave(editor) {
    return editor.querySelector(".airflow-json-memo")
        || editor.querySelector(".efficiency-json-memo")
        || editor.querySelector(".fan-control-json-memo")
        || editor.querySelector(".inline-memo");
}

function getVisibleMemoInput(editor) {
    return editor.querySelector(".special-visible-memo")
        || editor.querySelector(".inline-memo:not(.airflow-json-memo):not(.efficiency-json-memo):not(.fan-control-json-memo)");
}

function mergeSpecialVisibleMemo(editor) {
    const jsonMemo = editor.querySelector(".airflow-json-memo")
        || editor.querySelector(".efficiency-json-memo")
        || editor.querySelector(".fan-control-json-memo");

    const visibleMemo = editor.querySelector(".special-visible-memo");

    if (!jsonMemo) return;

    let data = {};

    try {
        data = JSON.parse(jsonMemo.value || "{}");
    } catch (e) {
        data = {};
    }

    data.userMemo = visibleMemo ? visibleMemo.value.trim() : "";
    jsonMemo.value = JSON.stringify(data);
}