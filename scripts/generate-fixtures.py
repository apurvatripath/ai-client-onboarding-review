from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle


OUTPUT_DIR = Path("output/pdf")
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

NAVY = colors.HexColor("#102A43")
BLUE = colors.HexColor("#2563EB")
PALE_BLUE = colors.HexColor("#EAF2FF")
SLATE = colors.HexColor("#52606D")
LINE = colors.HexColor("#D9E2EC")


def build_brief(filename: str, subtitle: str, fields: list[tuple[str, str]], note: str) -> None:
    styles = getSampleStyleSheet()
    title = ParagraphStyle(
        "FixtureTitle",
        parent=styles["Title"],
        fontName="Helvetica-Bold",
        fontSize=22,
        leading=27,
        textColor=NAVY,
        alignment=TA_LEFT,
        spaceAfter=5 * mm,
    )
    subtitle_style = ParagraphStyle(
        "FixtureSubtitle",
        parent=styles["BodyText"],
        fontName="Helvetica",
        fontSize=10,
        leading=15,
        textColor=SLATE,
        spaceAfter=7 * mm,
    )
    label_style = ParagraphStyle(
        "FieldLabel",
        parent=styles["BodyText"],
        fontName="Helvetica-Bold",
        fontSize=9,
        leading=12,
        textColor=NAVY,
    )
    value_style = ParagraphStyle(
        "FieldValue",
        parent=styles["BodyText"],
        fontName="Helvetica",
        fontSize=9,
        leading=13,
        textColor=colors.HexColor("#243B53"),
    )
    note_style = ParagraphStyle(
        "FixtureNote",
        parent=styles["BodyText"],
        fontName="Helvetica",
        fontSize=8.5,
        leading=12,
        textColor=SLATE,
        backColor=PALE_BLUE,
        borderColor=LINE,
        borderWidth=0.5,
        borderPadding=8,
    )

    document = SimpleDocTemplate(
        str(OUTPUT_DIR / filename),
        pagesize=A4,
        rightMargin=22 * mm,
        leftMargin=22 * mm,
        topMargin=20 * mm,
        bottomMargin=20 * mm,
        title="Fictional Client Onboarding Brief",
        author="AI Client Onboarding & Document Review Demo",
    )

    rows = [[Paragraph(label, label_style), Paragraph(value, value_style)] for label, value in fields]
    table = Table(rows, colWidths=[46 * mm, 105 * mm], hAlign="LEFT")
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#F5F8FB")),
                ("BOX", (0, 0), (-1, -1), 0.7, LINE),
                ("INNERGRID", (0, 0), (-1, -1), 0.4, LINE),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 9),
                ("RIGHTPADDING", (0, 0), (-1, -1), 9),
                ("TOPPADDING", (0, 0), (-1, -1), 8),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
            ]
        )
    )

    story = [
        Paragraph("Northstar Studio", title),
        Paragraph(subtitle, subtitle_style),
        table,
        Spacer(1, 7 * mm),
        Paragraph(note, note_style),
        Spacer(1, 8 * mm),
        Paragraph(
            "<b>Demonstration notice:</b> This brief is fictional and contains no real client information. "
            "Any follow-up generated from it must remain a human-approved draft.",
            subtitle_style,
        ),
    ]
    document.build(story)


COMMON = [
    ("Company name", "Northstar Studio"),
    ("Primary contact", "Alex Morgan"),
    ("Contact email", "alex@example.com"),
    ("Service requested", "Client onboarding automation"),
]

build_brief(
    "complete-fictional-client-brief.pdf",
    "Complete onboarding pack - expected result: COMPLETE",
    COMMON
    + [
        ("Budget", "USD 2,400 fixed-scope pilot"),
        ("Target launch date", "30 September 2026"),
        (
            "Project summary",
            "Build a secure intake workflow that validates a client brief, extracts operational details, "
            "records a review result and prepares a reply for human approval.",
        ),
        (
            "Dependencies",
            "Google Sheets access, Gemini API credential, approved onboarding checklist and one reviewer.",
        ),
    ],
    "All eight required onboarding fields are present and consistent with the submitted form.",
)

build_brief(
    "missing-information-fictional-client-brief.pdf",
    "Incomplete onboarding pack - expected result: MISSING_INFORMATION",
    COMMON
    + [
        ("Budget", "Not confirmed"),
        ("Target launch date", "30 September 2026"),
        (
            "Project summary",
            "Review uploaded onboarding documents and prepare a concise human-approved follow-up.",
        ),
        ("Dependencies", "Not provided"),
    ],
    "Budget and dependencies are intentionally absent. The workflow should ask only for those details.",
)

build_brief(
    "conflicting-fictional-client-brief.pdf",
    "Conflicting onboarding pack - expected result: MANUAL_REVIEW",
    [
        ("Company name", "Northstar Studio"),
        ("Primary contact", "Alex Morgan"),
        ("Contact email", "alex@example.com"),
        ("Service requested", "Client onboarding automation"),
        ("Budget", "USD 2,400 fixed-scope pilot"),
        ("Target launch date", "15 September 2026"),
        (
            "Project summary",
            "Build the validated onboarding and document-review workflow described in the submitted form.",
        ),
        ("Dependencies", "Google Sheets access, Gemini API credential and one reviewer."),
    ],
    "The target launch date intentionally conflicts with the form value of 30 September 2026.",
)
