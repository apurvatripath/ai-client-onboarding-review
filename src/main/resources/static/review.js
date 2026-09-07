const queueList = document.querySelector("#queue-list");
const queueEmpty = document.querySelector("#queue-empty");
const workspace = document.querySelector("#workspace");
const workspaceEmpty = document.querySelector("#workspace-empty");
const reviewId = document.querySelector("#review-id");
const reviewBadge = document.querySelector("#review-badge");
const reviewApproval = document.querySelector("#review-approval");
const reviewStatusBox = document.querySelector("#review-status");
const documentFrame = document.querySelector("#document-frame");
const documentMissing = document.querySelector("#document-missing");
const fieldsList = document.querySelector("#fields-list");
const lineItemsHeading = document.querySelector("#line-items-heading");
const lineItems = document.querySelector("#line-items");
const issuesWrapper = document.querySelector("#issues-wrapper");
const issuesList = document.querySelector("#issues-list");
const saveButton = document.querySelector("#save-button");
const approveButton = document.querySelector("#approve-button");

let currentId = null;
let documentUrl = null;

loadQueue();

async function loadQueue() {
    const response = await fetch("/api/extractions/review-queue");
    const items = response.ok ? await response.json() : [];
    queueList.replaceChildren();
    queueEmpty.hidden = items.length > 0;
    items.forEach((item) => {
        const li = document.createElement("li");
        const button = document.createElement("button");
        button.type = "button";
        button.className = "queue-item" + (item.submissionId === currentId ? " active" : "");
        button.innerHTML = '<strong>' + escapeHtml(item.submissionId) + '</strong>' +
            '<span class="queue-status">' + formatStatus(item.reviewStatus) + " · " + item.reviewQueue.length + " open</span>";
        button.addEventListener("click", () => loadItem(item.submissionId));
        li.appendChild(button);
        queueList.appendChild(li);
    });
}

async function loadItem(id) {
    const response = await fetch("/api/extractions/" + encodeURIComponent(id));
    if (!response.ok) return;
    const result = await response.json();
    currentId = result.submissionId;
    render(result);
    loadDocument(result.submissionId);
    loadQueue();
}

async function loadDocument(id) {
    if (documentUrl) URL.revokeObjectURL(documentUrl);
    documentUrl = null;
    documentFrame.hidden = true;
    documentMissing.hidden = true;
    const response = await fetch("/api/extractions/" + encodeURIComponent(id) + "/document");
    if (!response.ok) {
        documentMissing.hidden = false;
        return;
    }
    const blob = await response.blob();
    documentUrl = URL.createObjectURL(blob);
    documentFrame.src = documentUrl;
    documentFrame.hidden = false;
}

function render(result) {
    workspaceEmpty.hidden = true;
    workspace.hidden = false;
    reviewStatusBox.className = "form-status";
    reviewStatusBox.textContent = "";

    reviewId.textContent = "Submission " + result.submissionId + " · " + result.pageCount +
        (result.pageCount === 1 ? " page" : " pages") + " · " + result.provider;
    reviewBadge.textContent = formatStatus(result.reviewStatus);
    reviewBadge.classList.toggle("warning", result.reviewStatus !== "COMPLETE");
    reviewApproval.textContent = "Approval: " + formatStatus(result.approvalStatus);
    reviewApproval.classList.toggle("warning", result.approvalStatus !== "APPROVED");

    fieldsList.replaceChildren();
    Object.keys(result.fields).forEach((name) => {
        fieldsList.appendChild(fieldRow(name, result.fields[name]));
    });

    lineItemsHeading.hidden = result.lineItems.length === 0;
    lineItems.replaceChildren();
    if (result.lineItems.length > 0) {
        lineItems.appendChild(lineItemsTable(result.lineItems));
    }

    issuesWrapper.hidden = !Array.isArray(result.issues) || result.issues.length === 0;
    issuesList.replaceChildren();
    (result.issues || []).forEach((issue) => {
        const li = document.createElement("li");
        li.textContent = issue;
        issuesList.appendChild(li);
    });
}

function fieldRow(name, field) {
    const wrapper = document.createElement("div");
    wrapper.className = "field review-field";

    const label = document.createElement("label");
    label.textContent = prettify(name);
    label.setAttribute("for", "field-" + name);
    wrapper.appendChild(label);

    const input = document.createElement("input");
    input.type = "text";
    input.id = "field-" + name;
    input.dataset.field = name;
    input.value = field.value || "";
    wrapper.appendChild(input);

    const meta = document.createElement("div");
    meta.className = "field-meta review-field-meta";
    meta.innerHTML = "<span>" + statusLabel(field) + "</span>";
    wrapper.appendChild(meta);

    if (field.evidence) {
        const evidence = document.createElement("p");
        evidence.className = "review-evidence";
        evidence.textContent = "Evidence: “" + field.evidence + "”";
        wrapper.appendChild(evidence);
    }

    return wrapper;
}

function lineItemsTable(rows) {
    const table = document.createElement("table");
    table.className = "review-table";
    const head = table.insertRow();
    ["Description", "Quantity", "Unit price", "Status"].forEach((label) => {
        const th = document.createElement("th");
        th.textContent = label;
        head.appendChild(th);
    });
    rows.forEach((row, index) => {
        const tr = table.insertRow();
        ["description", "quantity", "unitPrice"].forEach((cell) => {
            const td = tr.insertCell();
            const input = document.createElement("input");
            input.type = "text";
            input.dataset.row = String(index);
            input.dataset.cell = cell;
            input.value = row[cell].value || "";
            td.appendChild(input);
        });
        const statusCell = tr.insertCell();
        statusCell.className = "review-table-status";
        statusCell.textContent = statusLabel(row.description);
    });
    return table;
}

function statusLabel(field) {
    if (field.status === "MISSING") return "Missing";
    if (field.status === "ACCEPTED") return "Accepted · " + Math.round(field.confidence * 100) + "%";
    return formatStatus(field.status) + " · " + Math.round(field.confidence * 100) + "%";
}

saveButton.addEventListener("click", () => submitReview(false));
approveButton.addEventListener("click", () => submitReview(true));

async function submitReview(approve) {
    if (!currentId) return;
    const fields = {};
    fieldsList.querySelectorAll("input[data-field]").forEach((input) => {
        fields[input.dataset.field] = input.value;
    });
    const rowCount = lineItems.querySelectorAll("tr").length - 1;
    const itemRows = [];
    for (let i = 0; i < rowCount; i++) {
        const row = {};
        lineItems.querySelectorAll('input[data-row="' + i + '"]').forEach((input) => {
            row[input.dataset.cell] = input.value;
        });
        itemRows.push(row);
    }

    saveButton.disabled = true;
    approveButton.disabled = true;
    try {
        const response = await fetch("/api/extractions/" + encodeURIComponent(currentId) + "/review", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({fields: fields, lineItems: itemRows, approve: approve})
        });
        const payload = await response.json();
        if (!response.ok) {
            reviewStatusBox.textContent = payload.message || "The review could not be saved.";
            reviewStatusBox.className = "form-status visible error";
            return;
        }
        render(payload);
        reviewStatusBox.textContent = approve ? "Saved and approved." : "Corrections saved.";
        reviewStatusBox.className = "form-status visible";
        loadQueue();
    } catch (error) {
        reviewStatusBox.textContent = "The review service could not be reached. Please try again shortly.";
        reviewStatusBox.className = "form-status visible error";
    } finally {
        saveButton.disabled = false;
        approveButton.disabled = false;
    }
}

function prettify(name) {
    return name.replace(/([A-Z])/g, " $1").replace(/^./, (c) => c.toUpperCase());
}

function formatStatus(status) {
    return (status || "")
        .toLowerCase()
        .split("_")
        .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
        .join(" ");
}

function escapeHtml(value) {
    const div = document.createElement("div");
    div.textContent = value;
    return div.innerHTML;
}
