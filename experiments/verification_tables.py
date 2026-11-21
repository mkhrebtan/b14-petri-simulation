"""Таблиці верифікації (підрозділ 3.4, додаток В) з CSV команди `verify`.

Запуск: python3 experiments/verification_tables.py (лише стандартна бібліотека; викликається з `just verify`).
Вхід: experiments/verification/variation.csv, single_change.csv.
Вихід: thesis/media/tab-verif-variation.tex (скорочена, у тексті), tab-verif-variation-full.tex (додаток В),
tab-verif-single.tex (зміна кожної вхідної змінної або параметра окремо). Файли не редагувати вручну.
"""
import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERIFICATION = ROOT / "experiments" / "verification"
MEDIA = ROOT / "thesis" / "media"

# Рядки скороченої таблиці в тексті: (s, γ); повна сітка — у додатку В.
SHORT_ROWS = [(1, 0.4), (1, 0.7), (2, 0.7), (3, 0.7), (4, 0.7), (5, 0.7)]

RUN = r"$T_{\text{мод}} = 3{,}5\cdot10^6$~хв, $T_{\text{п}} = 2\cdot10^4$~хв, 10 прогонів"

VARIATION_HEADER = r"""  {\textbf{$s$} & \textbf{$\gamma$} & \textbf{$L_q$,} & \textbf{$W_q$,} & \textbf{$W$,} &
   \textbf{$W_{q,A}$,} & \textbf{$W_{q,B}$,} & \textbf{$W_{q,C}$,} & \textbf{$U$} & \textbf{$\rho$} &
   \textbf{$\bar m_{\text{RC}}/M$} \\
   & & \textbf{завд.} & \textbf{хв} & \textbf{хв} & \textbf{хв} & \textbf{хв} & \textbf{хв} & & & }"""

SINGLE_HEADER = r"""  {\textbf{Змінена величина} & \textbf{$\rho$} & \textbf{$U$} & \textbf{$L_q$,} & \textbf{$W_q$,} &
   \textbf{$W$,} & \textbf{$W_{q,A}$,} & \textbf{$W_{q,B}$,} & \textbf{$W_{q,C}$,} & \textbf{$W_C$,} \\
   & & & \textbf{завд.} & \textbf{хв} & \textbf{хв} & \textbf{хв} & \textbf{хв} & \textbf{хв} & \textbf{хв}}"""

ORDERING = {"1": r"A~$>$~B~$>$~C", "2": r"B~$>$~A~$>$~C", "3": r"C~$>$~B~$>$~A",
            "4": r"C~$>$~A~$>$~B", "5": r"C~$>$~(A~=~B)"}


def num(value, digits=2):
    """Десяткова кома (ДСТУ 3008)."""
    return f"{float(value):.{digits}f}".replace(".", ",")


def variation_row(r):
    return " & ".join([r["s"], num(r["gamma"], 1), num(r["Lq"]), num(r["Wq"]), num(r["W"]),
                       num(r["Wq_A"]), num(r["Wq_B"]), num(r["Wq_C"]), num(r["U"], 3),
                       num(r["rho_theory"], 3), num(r["reserved_idle"], 3)]) + r" \\ \hline"


def variation_table(rows, caption, label):
    body = "\n".join("  " + variation_row(r) for r in rows)
    return (r"\begin{kpitable}{|c|c|r|r|r|r|r|r|r|r|r|}{" + caption + " (" + RUN + r")\label{" + label + "}}\n"
            + VARIATION_HEADER + "\n" + body + "\n" + r"\end{kpitable}" + "\n")


def change_label(r):
    """Мітка рядка: значення змінних базового рядка наведено в тексті підрозділу 3.5."""
    variable, change = r["variable"], r["change"]
    if variable == "base":
        return "базовий"
    if variable == "M":
        return r"$M = 3$"
    kind, t = variable.split("_")
    return (rf"$\lambda_{t}$" if kind == "lambda" else rf"$1/\mu_{t}$") + r" $\times\,2$"


def single_table(rows):
    lines = []
    for s in dict.fromkeys(r["s"] for r in rows):
        lines.append(rf"  \multicolumn{{10}}{{|l|}}{{стратегія {s} ({ORDERING[s]})}} \\ \hline")
        for r in (x for x in rows if x["s"] == s):
            cells = [change_label(r), num(r["rho_theory"], 3), num(r["U"], 3), num(r["Lq"]), num(r["Wq"]),
                     num(r["W"]), num(r["Wq_A"]), num(r["Wq_B"]), num(r["Wq_C"]), num(r["W_C"])]
            lines.append("  " + " & ".join(cells) + r" \\ \hline")
    return (r"\begin{kpitable}{|l|r|r|r|r|r|r|r|r|r|}{Відгуки моделі за зміни однієї вхідної змінної або параметра ("
            + RUN + r")\label{tab:verif-single}}" + "\n" + SINGLE_HEADER + "\n" + "\n".join(lines) + "\n"
            + r"\end{kpitable}" + "\n")


def main():
    variation = list(csv.DictReader(open(VERIFICATION / "variation.csv", encoding="utf-8")))
    single = list(csv.DictReader(open(VERIFICATION / "single_change.csv", encoding="utf-8")))
    short = [r for key in SHORT_ROWS for r in variation
             if (int(r["s"]), round(float(r["gamma"]), 2)) == key]
    (MEDIA / "tab-verif-variation.tex").write_text(variation_table(
        short, "Відгуки моделі за зміни стратегії та коефіцієнта інтенсивності, вибрані точки",
        "tab:verif-variation"), encoding="utf-8")
    (MEDIA / "tab-verif-variation-full.tex").write_text(variation_table(
        variation, "Відгуки моделі за зміни стратегії та коефіцієнта інтенсивності",
        "tab:verif-variation-full"), encoding="utf-8")
    (MEDIA / "tab-verif-single.tex").write_text(single_table(single), encoding="utf-8")

    # Підсумки для тексту підрозділу 3.5
    rel_ci = max(float(r["W_ci_half_width"]) / float(r["W"]) for r in variation + single) * 100
    du = max(abs(float(r["U"]) - float(r["rho_theory"])) for r in variation + single)
    print(f"max ±W (0,95) / W = {rel_ci:.2f} %, max |U - rho| = {du:.4f}")


if __name__ == "__main__":
    main()
