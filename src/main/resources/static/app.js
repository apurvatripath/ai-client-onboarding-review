const form = document.querySelector("#onboarding-form");
const submitButton = document.querySelector("#submit-button");
const formStatus = document.querySelector("#form-status");
const resultPanel = document.querySelector("#result-panel");
const documentInput = document.querySelector("#document");
const uploadTitle = document.querySelector("#upload-title");
const uploadDetail = document.querySelector("#upload-detail");
const notes = document.querySelector("#notes");
const notesCount = document.querySelector("#notes-count");
const newSubmissionButton = document.querySelector("#new-submission");

const allowedTypes = new Set(["application/pdf", "image/jpeg", "image/png"]);
const maxFileSize = 5 * 1024 * 1024;

document.querySelector("#desiredStartDate").min = new Date().toISOString().split("T")[0];

notes.addEventListener("input", () => {
    notesCount.textContent = notes.value.length + " / 1000";
});

documentInput.addEventListener("change", () => {
    clearFieldError("document");
    const file = documentInput.files[0];
    if (!file) {
        resetUploadCopy();
        return;
    }
    uploadTitle.textContent = file.name;
    uploadDetail.textContent = formatFileSize(file.size) + " · ready for validation";
});

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    clearErrors();

    if (!validateClientSide()) {
        showFormError("Please correct the highlighted fields.");
        return;
    }

    setSubmitting(true);
    try {
        const response = await fetch("/api/onboarding", {
            method: "POST",
            body: new FormData(form)
        });
        const payload = await response.json();

        if (!response.ok) {
            applyServerErrors(payload);
            showFormError(payload.message || "The review could not be completed.");
            return;
        }

        showResult(payload);
    } catch (error) {
        showFormError("The review service could not be reached. Please try again shortly.");
    } finally {
        setSubmitting(false);
    }
});

newSubmissionButton.addEventListener("click", () => {
    resultPanel.hidden = true;
    form.hidden = false;
    form.reset();
    notesCount.textContent = "0 / 1000";
    resetUploadCopy();
    clearErrors();
    formStatus.className = "form-status";
    window.scrollTo({top: 0, behavior: "smooth"});
});

function validateClientSide() {
    let valid = true;

    for (const element of form.elements) {
        if (!(element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement)) {
            continue;
        }
        if (!element.checkValidity()) {
            setFieldError(element.name, friendlyValidationMessage(element));
            valid = false;
        }
    }

    const file = documentInput.files[0];
    if (file && !allowedTypes.has(file.type)) {
        setFieldError("document", "Only PDF, JPG and PNG files are accepted.");
        valid = false;
    } else if (file && file.size > maxFileSize) {
        setFieldError("document", "The document must be 5 MB or smaller.");
        valid = false;
    }

    return valid;
}

function friendlyValidationMessage(element) {
    if (element.validity.valueMissing) {
        return "This field is required.";
    }
    if (element.validity.typeMismatch) {
        return element.type === "email" ? "Enter a valid email address." : "Enter a complete URL including https://.";
    }
    if (element.validity.tooShort) {
        return "Enter at least " + element.minLength + " characters.";
    }
    return "Check this value and try again.";
}

function applyServerErrors(payload) {
    if (!Array.isArray(payload.fieldErrors)) {
        return;
    }
    payload.fieldErrors.forEach((error) => setFieldError(error.field, error.message));
}

function setFieldError(field, message) {
    const input = document.querySelector('[name="' + CSS.escape(field) + '"]');
    const error = document.querySelector("#" + CSS.escape(field) + "-error");
    if (input) {
        input.setAttribute("aria-invalid", "true");
        input.setAttribute("aria-describedby", field + "-error");
    }
    if (error) {
        error.textContent = message;
    }
}

function clearFieldError(field) {
    const input = document.querySelector('[name="' + CSS.escape(field) + '"]');
    const error = document.querySelector("#" + CSS.escape(field) + "-error");
    if (input) {
        input.removeAttribute("aria-invalid");
        input.removeAttribute("aria-describedby");
    }
    if (error) {
        error.textContent = "";
    }
}

function clearErrors() {
    form.querySelectorAll("[name]").forEach((element) => clearFieldError(element.name));
    formStatus.className = "form-status";
    formStatus.textContent = "";
}

function showFormError(message) {
    formStatus.textContent = message;
    formStatus.className = "form-status visible error";
}

function setSubmitting(submitting) {
    submitButton.disabled = submitting;
    submitButton.firstElementChild.textContent = submitting ? "Reviewing document…" : "Review onboarding pack";
}

function showResult(payload) {
    const missing = Array.isArray(payload.missingFields) ? payload.missingFields : [];
    const isComplete = payload.reviewStatus === "COMPLETE";
    const badge = document.querySelector("#result-badge");

    badge.textContent = formatStatus(payload.reviewStatus);
    badge.classList.toggle("warning", !isComplete);
    document.querySelector("#result-title").textContent = isComplete
        ? "The onboarding pack is complete."
        : "The onboarding pack needs attention.";
    document.querySelector("#result-reason").textContent = payload.reviewReason || "The workflow returned a review status.";
    document.querySelector("#submission-id").textContent = payload.submissionId;

    const missingWrapper = document.querySelector("#missing-wrapper");
    const missingList = document.querySelector("#missing-list");
    missingList.replaceChildren();
    missing.forEach((field) => {
        const item = document.createElement("li");
        item.textContent = field;
        missingList.appendChild(item);
    });
    missingWrapper.hidden = missing.length === 0;

    const draftWrapper = document.querySelector("#draft-wrapper");
    document.querySelector("#draft-copy").textContent = payload.followUpDraft || "";
    draftWrapper.hidden = !payload.followUpDraft;

    form.hidden = true;
    resultPanel.hidden = false;
    resultPanel.scrollIntoView({behavior: "smooth", block: "start"});
}

function formatStatus(status) {
    return (status || "MANUAL_REVIEW")
        .toLowerCase()
        .split("_")
        .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
        .join(" ");
}

function formatFileSize(bytes) {
    if (bytes < 1024 * 1024) {
        return Math.max(1, Math.round(bytes / 1024)) + " KB";
    }
    return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}

function resetUploadCopy() {
    uploadTitle.textContent = "Choose one supporting document";
    uploadDetail.textContent = "PDF, JPG or PNG · maximum 5 MB";
}

