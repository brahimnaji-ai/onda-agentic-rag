"""Rebuild the frozen ONDA corpus and draft relevance labels from local source PDFs.

Requires pypdf. No model calls and no network access. IDs depend on source hashes,
physical page number and chunk position, never on questions or reference answers.
"""
import hashlib
import json
import re
import unicodedata
import uuid
from pathlib import Path

from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evaluation" / "onda-v1"
PREFIXES = {"013", "057", "060", "061", "063", "065", "067", "072", "073", "075"}


def normalize(text):
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text)).strip()


def build():
    chunks, sources = [], []
    for path in sorted((ROOT / "documents" / "onda").glob("*.pdf")):
        if path.name[:3] not in PREFIXES:
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        doc_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "onda-eval:" + digest))
        sources.append({"documentId": doc_id, "source": path.name, "sha256": digest})
        for page, pdf_page in enumerate(PdfReader(path).pages, 1):
            text = normalize(pdf_page.extract_text() or "")
            # Remove public press contacts, which are irrelevant to retrieval evaluation.
            text = re.split(r"Contact presse\s*:", text, flags=re.I)[0].strip()
            words = text.split()
            for start in range(0, len(words), 120):
                content = " ".join(words[start:start + 160])
                if not content:
                    continue
                chunks.append({"id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:{page}:{start}")),
                               "documentId": doc_id, "source": path.name, "page": page,
                               "chunkIndex": start // 120, "text": content})
    if len(sources) != len(PREFIXES):
        raise ValueError("All ten source PDFs are required to rebuild this version")

    # Anchors locate evidence during authoring only; frozen fixtures store IDs and grades.
    topics = [
        ("bus", "073", "25 dirhams", "RBA / Rabat-Salé", [
            "À quelle fréquence passe le bus de l'aéroport de Rabat-Salé et quel est son tarif ?",
            "How often does the bus leave RBA and can I pay by bank card?",
            "ما ثمن حافلة مطار الرباط سلا وما وتيرة انطلاقها؟",
            "Et est-ce que je peux payer le bus par carte ?"]),
        ("taxi", "073", "Wetaxi", "le transport à Rabat-Salé", [
            "Quel service de taxi numérique est annoncé à l'aéroport de Rabat-Salé ?",
            "What regulated digital taxi option serves Rabat-Salé airport?",
            "ما خدمة سيارات الأجرة الرقمية المتاحة في مطار الرباط سلا؟",
            "Et quelle alternative numérique au bus est proposée ?"]),
        ("apoc", "075", "vingt-quatre heures", "l'APOC à CMN", [
            "Quels processus l'APOC de Casablanca coordonne-t-il et quand fonctionne-t-il ?",
            "Does CMN's APOC operate around the clock and coordinate baggage flows?",
            "هل يعمل مركز APOC بمطار محمد الخامس طوال اليوم وما مهامه؟",
            "Et fonctionne-t-il aussi la nuit ?"]),
        ("terminal", "060", "15 milliards", "le futur terminal de CMN", [
            "Quelle capacité, quel investissement et quelle année de livraison sont annoncés pour le nouveau terminal de CMN ?",
            "What capacity and delivery year were announced for the new Casablanca hub terminal?",
            "ما الطاقة الاستيعابية وتكلفة وموعد تسليم مبنى مطار محمد الخامس الجديد حسب الإعلان؟",
            "Et combien de passagers pourra-t-il accueillir par an ?"]),
        ("gates", "061", "deuxième fois", "les portillons automatiques de Casablanca", [
            "Quel double contrôle a été supprimé à Casablanca grâce aux portillons automatiques ?",
            "Which repeated passport check do the automatic gates replace at CMN?",
            "ما المراقبة المزدوجة التي ألغتها البوابات الآلية في مطار محمد الخامس؟",
            "Et quel contrôle remplaçaient-ils exactement ?"]),
        ("entry", "063", "déplacés", "le communiqué n°02-2025 et l'entrée de CMN", [
            "Selon le communiqué n°02-2025, qu'est-il arrivé aux scanners et portiques aux entrées de Mohammed V ?",
            "Under press release n°02-2025, were entrance scanners at CMN relocated?",
            "وفق البلاغ رقم 02-2025، ماذا تغير في أجهزة التفتيش عند مداخل مطار محمد الخامس؟",
            "Et quel changement aux entrées décrit ce communiqué ?"]),
        ("queues", "065", "2,25 minutes", "les temps de contrôle à CMN au premier semestre 2026", [
            "Quels temps moyens au départ et à l'arrivée sont indiqués pour Casablanca au premier semestre 2026 ?",
            "How long did CMN departure and arrival border checks take in the first half of 2026?",
            "كم بلغ متوسط مدة مراقبة الحدود عند المغادرة والوصول في الدار البيضاء خلال النصف الأول من 2026؟",
            "Et quelle était la durée moyenne au départ ?"]),
        ("traffic", "072", "36,3 millions", "le trafic aérien national 2025", [
            "Quel trafic national est annoncé pour 2025 et comment évolue-t-il par rapport à 2024 ?",
            "What passenger total and year-on-year growth did ONDA report for 2025?",
            "كم بلغ عدد المسافرين سنة 2025 وما نسبة الزيادة مقارنة بسنة 2024؟",
            "Et de combien a-t-il progressé par rapport à 2024 ?"]),
        ("mobile", "067", "QR code sécurisé", "la carte d'embarquement mobile Pax Check", [
            "Comment un passager reçoit-il sa carte d'embarquement mobile avec Pax Check ?",
            "Do passengers need a printed boarding pass after online check-in under Pax Check?",
            "هل يحتاج المسافر إلى بطاقة صعود مطبوعة بعد التسجيل عبر الإنترنت مع Pax Check؟",
            "Et faut-il encore imprimer la carte ?"]),
        ("confidentiality", "013", "après son départ", "la section II.6 du Code d'éthique", [
            "Selon la section II.6 du Code d'éthique, la confidentialité continue-t-elle après le départ de l'Office ?",
            "Does section II.6 of ONDA's ethics code require confidentiality after leaving the Office?",
            "هل يستمر واجب السرية بعد مغادرة المكتب حسب القسم II.6 من مدونة الأخلاق؟",
            "Et cette obligation continue-t-elle après le départ de l'Office ?"]),
        ("conflicts", "013", "intérêts personnels ou financiers", "la section II.7 du Code d'éthique", [
            "Quels conflits d'intérêts les collaborateurs doivent-ils éviter selon la section II.7 ?",
            "What personal or financial conflicts must staff avoid under ethics section II.7?",
            "ما تضارب المصالح الشخصية أو المالية الواجب تجنبه حسب القسم II.7؟",
            "Et cela concerne-t-il aussi la sélection des fournisseurs ?"]),
        ("gifts", "013", "II.9.1.", "la section II.9.1 du Code d'éthique", [
            "Quels cadeaux et faveurs sont visés par la section II.9.1 du Code d'éthique ?",
            "How does ethics section II.9.1 describe gifts and favours from business partners?",
            "كيف يعرّف القسم II.9.1 الهدايا والمزايا المقدمة من شركاء الأعمال؟",
            "Et comment les cadeaux et faveurs y sont-ils définis ?"]),
    ]
    cases, labels = [], {}
    for name, prefix, anchor, context, questions in topics:
        hits = [c for c in chunks if c["source"].startswith(prefix) and anchor in c["text"]]
        if not hits:
            raise ValueError(f"Evidence anchor not found: {name}")
        labels[name] = [{"documentId": c["documentId"], "chunkId": c["id"], "grade": 1} for c in hits]
        for i, question in enumerate(questions):
            tags = ["policy-reference" if prefix == "013" or name == "entry" else "airport-operations"]
            if i == 3:
                tags.append("follow-up")
            if "CMN" in question or "RBA" in question:
                tags.append("airport-code")
            cases.append({"id": f"{name}-{i + 1}", "language": ["fr", "en", "ar", "fr"][i],
                          "question": question, "history": [f"Nous parlons de {context}."] if i == 3 else [],
                          "hard": i > 0, "tags": tags, "answerable": True, "relevance": labels[name]})
    for i, (a, b, question) in enumerate([
        ("bus", "taxi", "À RBA, comparez le bus et Wetaxi : fréquence, prix connu et paiement par carte."),
        ("gates", "entry", "À CMN, distinguez la suppression du double contrôle et le déplacement des scanners d'entrée."),
        ("terminal", "apoc", "Compare Casablanca's planned terminal capacity with the operating role of its APOC."),
        ("traffic", "queues", "Compare the 2025 passenger growth with CMN border waiting times in the first half of 2026."),
        ("confidentiality", "conflicts", "ما واجبات السرية وتجنب تضارب المصالح حسب II.6 وII.7؟"),
        ("mobile", "gates", "كيف تختلف بطاقة الصعود الرقمية عن البوابات التي تعوض المراقبة المزدوجة؟")], 1):
        cases.append({"id": f"multipart-{i}", "language": "fr" if i < 3 else "en" if i < 5 else "ar",
                      "question": question, "history": [], "hard": True, "tags": ["multi-part"],
                      "answerable": True, "relevance": list({x["chunkId"]: x for x in labels[a] + labels[b]}.values())})
    for i, (language, question) in enumerate([
        ("fr", "Quel est le mot de passe Wi-Fi privé du bureau du directeur de CMN ?"),
        ("en", "What is the exact taxi fare to every hotel in Rabat in 2035?"),
        ("ar", "ما رقم جواز سفر مدير مطار محمد الخامس؟"),
        ("fr", "Donnez le texte de la politique ONDA-SEC-9999 absente des documents fournis."),
        ("en", "What gate will tomorrow's flight AT999 use at RAK?"),
        ("ar", "ما العدد النهائي للمسافرين في سنة 2040؟")], 1):
        cases.append({"id": f"unanswerable-{i}", "language": language, "question": question,
                      "history": [], "hard": True, "tags": ["unanswerable"], "answerable": False, "relevance": []})
    OUT.mkdir(parents=True, exist_ok=True)
    for name, data in {"corpus.json": {"version": "onda-v1", "sources": sources, "chunks": chunks},
                       "questions.json": {"version": "onda-v1", "labelsReviewed": False, "questions": cases}}.items():
        (OUT / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(chunks)} real-source chunks and {len(cases)} draft-labeled questions")


if __name__ == "__main__":
    build()
