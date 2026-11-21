"""Аналіз результатів експериментального дослідження моделі B14 (розділ 4 записки).

Вхід — CSV, отримані командою `just experiments` у sim/ (каталог experiments/results):
  trajectories_long.csv     — середні W, L_q, W_q на [0; t], 5 прогонів (критерій 3.1 → T_мод)
  trajectories_short.csv    — поточна довжина черги, 50 прогонів (ансамблеве середнє → T_п)
  trajectories_unstable.csv — γ = 1 (умова задачі), нестійкий режим
  pilot.csv                 — 100 прогонів у точках γ = 0,7 (σ, гістограма, кількість прогонів)
  factorial.csv             — план «стратегія × γ», p прогонів у точці (ANOVA, регресія)
  improvement.csv           — пробний запуск пропозиції розділу 5: M = 3, γ від 0,4 до 1,0
  trajectories_improved.csv — M = 3, γ = 1: середні на [0; t], 5 прогонів (критерій 3.1)
  trajectories_boundary.csv — γ = 0,75 і 0,8 (понад план): середні на [0; t] до 8·10^6 хв, 5 прогонів
  boundary.csv              — γ = 0,75 і 0,8: 10 прогонів у точці (коефіцієнт використання пам'яті U)
  flows.csv                 — змінюється лише потік C: λ_C = γ_C·0,6·λ_C^0, стратегії 1 і 4

Вихід:
  thesis/media/exp-*.pdf    — рисунки розділу 4, imp-*.pdf — розділу 5
  thesis/media/tab-*.tex    — таблиці розділів 4, 5 і додатка (kpitable)
  experiments/analysis/*.csv, summary.txt — проміжні результати, на які спирається текст

Запуск: experiments/.venv/bin/python experiments/analysis.py
"""

from __future__ import annotations

import io
import math
from decimal import Decimal
import warnings
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
# numpy 2 з Accelerate (macOS) видає хибні RuntimeWarning у matmul
warnings.filterwarnings("ignore", category=RuntimeWarning, module="numpy")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from matplotlib.ticker import FuncFormatter, MultipleLocator  # noqa: E402
from scipy import stats  # noqa: E402

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"
OUT = ROOT / "analysis"
MEDIA = ROOT.parent / "thesis" / "media"

# Умови проведення експерименту (обґрунтування — підрозділи 4.2–4.3)
T_WARMUP = 20_000          # T_п, хв
T_MOD = 3_500_000          # T_мод, хв
EPS_TRANSIENT = 0.10       # ε у критерії (3.1), частка сталого значення
EPS_RESULT = 0.02          # ε точності результату одного експерименту, частка середнього
BETA = 0.95                # довірча ймовірність
ALPHA = 1 - BETA
RHO0 = 1.175               # частка пам'яті, потрібна потоку за γ = 1 (формула 1.1)
LAMBDA0_SUM = 0.33         # λ_Σ^0 = 0,2 + 0,07 + 0,06 завд./хв; λ_Σ = 0,33·γ
LAMBDA_LABEL = "Сумарна інтенсивність надходження $\\lambda_\\Sigma = 0{,}33\\gamma$, завд./хв"
RESPONSES = {"W": ("W", "хв"), "Lq": ("L_q", "завд."), "Wq": ("W_q", "хв")}
ORDERING = {1: "A > B > C", 2: "B > A > C", 3: "C > B > A", 4: "C > A > B", 5: "C > (A = B)"}

# Категоріальна палітра (перевірена validate_palette.js) + маркери й типи ліній для друку
COLORS = {1: "#2a78d6", 2: "#eb6834", 3: "#1baf7a", 4: "#eda100", 5: "#e87ba4"}
MARKERS = {1: "o", 2: "s", 3: "^", 4: "D", 5: "v"}
LINESTYLES = {1: "-", 2: "--", 3: "-", 4: "--", 5: ":"}

plt.rcParams.update({
    "font.family": "Times New Roman",
    "mathtext.fontset": "stix",
    "font.size": 12,
    "axes.titlesize": 12,
    "axes.labelsize": 12,
    "legend.fontsize": 11,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.grid": True,
    "grid.color": "#d9d9d9",
    "grid.linewidth": 0.6,
    "axes.edgecolor": "#555555",
    "lines.linewidth": 1.6,
    "pdf.fonttype": 42,
    "savefig.bbox": "tight",
})

summary = io.StringIO()


def log(*args):
    print(*args)
    print(*args, file=summary)


def comma(x: float, digits: int = 2) -> str:
    """Число з десятковою комою й тонким пробілом між тисячами (для LaTeX)."""
    if isinstance(x, float) and math.isnan(x):
        return "--"
    s = f"{x:,.{digits}f}".replace(",", " ").replace(".", ",").replace(" ", "\\,")
    return "$-$" + s[1:] if s.startswith("-") else s


def axis_comma(digits: int | None = None):
    def fmt(v, _pos):
        text = f"{v:.{digits}f}" if digits is not None else f"{v:g}"
        return text.replace(".", ",").replace("-", "−")
    return FuncFormatter(fmt)


def lambda_sum(gamma: float) -> str:
    """λ_Σ = 0,33·γ, завд./хв, без зайвих нулів (для таблиць)."""
    return f"{LAMBDA0_SUM * gamma:.4f}".rstrip("0").replace(".", ",")


def add_lambda_axis(ax, step: float):
    """Друга (верхня) вісь абсцис — сумарна інтенсивність надходження λ_Σ = 0,33·γ, завд./хв."""
    sec = ax.secondary_xaxis("top", functions=(lambda g: LAMBDA0_SUM * g, lambda lam: lam / LAMBDA0_SUM))
    sec.set_xlabel(LAMBDA_LABEL)
    sec.xaxis.set_major_locator(MultipleLocator(step))
    sec.xaxis.set_major_formatter(axis_comma(2))
    return sec


def save(fig, name: str):
    # Без дати створення: повторний запуск дає ті самі PDF байт у байт
    fig.savefig(MEDIA / f"{name}.pdf", metadata={"CreationDate": None})
    fig.savefig(OUT / f"{name}.png", dpi=150)
    plt.close(fig)


def write_table(name: str, colspec: str, caption: str, label: str, header: str, rows: list[str],
                colsep: str | None = None):
    """colsep — зменшений відступ \\tabcolsep для широких таблиць (типово 6 pt)."""
    body = "\n".join(f"  {r} \\\\ \\hline" for r in rows)
    table = (f"\\begin{{kpitable}}{{{colspec}}}{{{caption}\\label{{{label}}}}}\n"
             f"  {{{header}}}\n{body}\n\\end{{kpitable}}\n")
    if colsep:
        table = f"\\begingroup\\setlength{{\\tabcolsep}}{{{colsep}}}\n{table}\\endgroup\n"
    text = f"% Згенеровано experiments/analysis.py — не редагувати вручну\n{table}"
    (MEDIA / f"{name}.tex").write_text(text, encoding="utf-8")


# ----------------------------------------------------------------------------------------
# 1. Перехідний період і час моделювання
# ----------------------------------------------------------------------------------------

def criterion_time(group: pd.DataFrame, response: str, eps: float) -> tuple[float, float]:
    """Найменший T сітки, після якого розмах середніх на [0; t] між прогонами < ε·ȳ (3.1)."""
    p = group.pivot(index="t", columns="run", values=response)
    final = p.iloc[-1].mean()
    spread = (p.max(axis=1) - p.min(axis=1)) / final
    bad = spread[spread >= eps]
    step = p.index[1] - p.index[0]
    t = (bad.index.max() + step) if len(bad) else p.index[0]
    return t, final


def transient_analysis():
    long = pd.read_csv(RESULTS / "trajectories_long.csv")
    records = []
    for (s, g), grp in long.groupby(["s", "gamma"]):
        for r in RESPONSES:
            t, final = criterion_time(grp, r, EPS_TRANSIENT)
            records.append({"s": s, "gamma": g, "response": r, "T_eps": t, "final_mean": final})
    crit = pd.DataFrame(records)
    crit.to_csv(OUT / "criterion_3_1.csv", index=False)
    gammas = sorted(crit.gamma.unique())
    rows = []
    for s in sorted(crit.s.unique()):
        cells = [comma(crit[(crit.s == s) & (crit.gamma == g) & (crit.response == r)].T_eps.iloc[0] / 1e6, 2)
                 for g in gammas for r in RESPONSES]
        rows.append(f"{s} & " + " & ".join(cells))
    t_max = crit.T_eps.max()
    log(f"[3.1] ε = {EPS_TRANSIENT:.0%}: найбільший T = {t_max:.0f} хв "
        f"(s={crit.loc[crit.T_eps.idxmax(), 's']}, γ={crit.loc[crit.T_eps.idxmax(), 'gamma']}, "
        f"{crit.loc[crit.T_eps.idxmax(), 'response']}) → T_мод = {T_MOD}")
    for g in gammas:
        log(f"      γ={g}: max T = {crit[crit.gamma == g].T_eps.max():.0f}")
    # Порівняння двох точностей (ε = 10 % у (3.1) і ε = 2 % результату): розмах середніх 5 прогонів
    # у момент T_мод і критерій (3.1) з ε = 2 % на всій довжині траєкторій
    spreads = []
    for (s, g), grp in long.groupby(["s", "gamma"]):
        for r in RESPONSES:
            p_ = grp.pivot(index="t", columns="run", values=r)
            row = p_.loc[p_.index[p_.index >= T_MOD][0]]
            spreads.append({"s": s, "gamma": g, "response": r,
                            "spread": (row.max() - row.min()) / p_.iloc[-1].mean(),
                            "T_eps2": criterion_time(grp, r, EPS_RESULT)[0]})
    spreads = pd.DataFrame(spreads)
    worst = spreads.loc[spreads.spread.idxmax()]
    t_end = long.t.max()
    unmet = (spreads.T_eps2 > t_end).sum()
    log(f"[3.1] розмах середніх 5 прогонів у момент T_мод: до {worst.spread:.2%} (s={worst.s}, γ={worst.gamma}, "
        f"{worst.response}); за γ = 0,4 — до {spreads[spreads.gamma == 0.4].spread.max():.2%}")
    log(f"[3.1] ε = {EPS_RESULT:.0%}: критерій не виконано до t = {t_end:.0f} хв у {unmet} з {len(spreads)} "
        f"випадків; оцінка T ~ розмах² для одного прогону: "
        f"{T_MOD * (worst.spread / EPS_RESULT) ** 2:.3g} хв")
    resp = " & ".join(f"\\textbf{{${RESPONSES[r][0]}$}}" for r in RESPONSES)
    write_table(
        "tab-exp-criterion", "|c|r|r|r|r|r|r|",
        "Найменший час моделювання за критерієм~\\eqref{eq:criterion} ($\\varepsilon = 10$~\\% "
        "сталого значення, 5 прогонів), $10^6$~хв",
        "tab:exp-criterion",
        " & " + " & ".join(f"\\multicolumn{{3}}{{c|}}{{\\textbf{{$\\gamma = {comma(g, 1).replace(',', '{,}')}$}}}}"
                           for g in gammas)
        + " \\\\ \\cline{2-7}\n  \\textbf{$s$} & " + resp + " & " + resp,
        rows)

    # Рисунок: середній W на [0; t] для 5 прогонів у двох точках
    fig, axes = plt.subplots(1, 2, figsize=(16.5 / 2.54, 7.6 / 2.54))
    for ax, (s, g), letter in zip(axes, [(1, 0.4), (5, 0.7)], "аб"):
        grp = long[(long.s == s) & (long.gamma == g)]
        t_eps, final = criterion_time(grp, "W", EPS_TRANSIENT)
        for run, r in grp.groupby("run"):
            ax.plot(r.t / 1e6, r.W, lw=1.1, color="#555555", alpha=0.35 + 0.13 * run)
        ax.axhspan(final * (1 - EPS_TRANSIENT / 2), final * (1 + EPS_TRANSIENT / 2),
                   color=COLORS[1], alpha=0.12, lw=0)
        ax.axvline(t_eps / 1e6, color=COLORS[2], ls="--", lw=1.2)
        ax.set_xlabel("$t$, $10^6$ хв")
        ax.set_ylabel("$\\bar W(t)$, хв")
        ax.set_title(f"{letter}) $s = {s}$, $\\gamma = {comma(g, 1).replace(',', '{,}')}$", loc="left")
        lo, hi = final * 0.75, final * 1.25
        ax.set_ylim(lo, hi)
        ax.set_xlim(0, 5)
        ax.xaxis.set_major_formatter(axis_comma())
        ax.yaxis.set_major_formatter(axis_comma())
    fig.tight_layout()
    save(fig, "exp-criterion")

    # Рисунок: ансамблеве середнє поточної довжини черги (50 прогонів), γ = 0,7
    short = pd.read_csv(RESULTS / "trajectories_short.csv")
    fig, ax = plt.subplots(figsize=(16.5 / 2.54, 7.0 / 2.54))
    lines = []
    for s, grp in short.groupby("s"):
        m = grp.groupby("t").Lq_now.mean()
        sm_ = m.rolling(8, min_periods=1).mean()  # ковзне вікно 2000 хв
        steady = m[m.index > T_WARMUP].mean()
        log(f"[T_п] s={s}: ансамблеве середнє L_q*(t) на t > T_п = {steady:.2f}; "
            f"t=1000: {m.loc[1000]:.2f}, t=2000: {m.loc[2000]:.2f}, t=5000: {m.loc[5000]:.2f}")
        (line,) = ax.plot(np.r_[0, sm_.index] / 1000, np.r_[0, sm_.values], color=COLORS[s],
                          ls=LINESTYLES[s], lw=1.3, label=f"$s = {s}$")
        lines.append(line)
    ax.axvline(T_WARMUP / 1000, color="#333333", ls="--", lw=1.1)
    ax.annotate("$T_{\\mathrm{п}}$", (T_WARMUP / 1000, ax.get_ylim()[1] * 0.92),
                xytext=(4, 0), textcoords="offset points")
    ax.set_xlabel("$t$, $10^3$ хв")
    ax.set_ylabel("$\\langle L_q^*(t)\\rangle$, завд.")
    ax.set_xlim(0, 60)
    ax.legend(ncol=5, loc="lower center", bbox_to_anchor=(0.5, 1.0), frameon=False)
    ax.yaxis.set_major_formatter(axis_comma())
    save(fig, "exp-warmup")

    # Рисунок: γ = 1 — середня довжина черги зростає з часом моделювання
    uns = pd.read_csv(RESULTS / "trajectories_unstable.csv")
    fig, ax = plt.subplots(figsize=(16.5 / 2.54, 6.5 / 2.54))
    for s, grp in uns.groupby("s"):
        m = grp.groupby("t").Lq.mean()
        ax.plot(m.index / 1000, m.values, color=COLORS[s], ls=LINESTYLES[s], label=f"$s = {s}$")
        log(f"[γ=1] s={s}: L_q(t) на [0; t]: t=50k {m.loc[50000]:.0f}, t=100k {m.loc[100000]:.0f}, "
            f"t={m.index[-1]:.0f} {m.iloc[-1]:.0f}")
    ax.set_xlabel("$t$, $10^3$ хв")
    ax.set_ylabel("$\\bar L_q(t)$, завд.")
    ax.set_xlim(0, m.index[-1] / 1000)
    ax.legend(ncol=5, loc="lower center", bbox_to_anchor=(0.5, 1.0), frameon=False)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _p: f"{v:,.0f}".replace(",", " ")))
    save(fig, "exp-unstable")


# ----------------------------------------------------------------------------------------
# 2. Кількість прогонів
# ----------------------------------------------------------------------------------------

def runs_chebyshev(sd: float, eps: float) -> float:
    """Формула (3.4) посібника: p = σ² / (ε²(1 − β))."""
    return sd ** 2 / (eps ** 2 * (1 - BETA))


def runs_clt(sd: float, eps: float) -> float:
    """За центральною граничною теоремою: p = (z σ / ε)², z = 1,96."""
    return (stats.norm.ppf(1 - ALPHA / 2) * sd / eps) ** 2


CHI2_BINS = 10


def chi2_normal(x: np.ndarray) -> tuple[float, float]:
    """Критерій згоди χ² з нормальним законом: 10 рівноймовірних інтервалів, параметри — за
    вибіркою (ступенів вільності 10 − 1 − 2 = 7). Повертає χ² і критичне значення (α = 0,05)."""
    m, sd = x.mean(), x.std(ddof=1)
    edges = stats.norm.ppf(np.linspace(0, 1, CHI2_BINS + 1)[1:-1], m, sd)
    observed = np.bincount(np.searchsorted(edges, x), minlength=CHI2_BINS)
    expected = len(x) / CHI2_BINS
    chi2 = ((observed - expected) ** 2 / expected).sum()
    return chi2, stats.chi2.ppf(1 - ALPHA, CHI2_BINS - 3)


def pilot_analysis() -> int:
    pilot = pd.read_csv(RESULTS / "pilot.csv")
    rows, p_needed = [], []
    for s, g in pilot.groupby("s"):
        rel, chis, runs = [], [], []
        for r in RESPONSES:
            x = g[r]
            m, sd = x.mean(), x.std(ddof=1)
            chi2, chi2_crit = chi2_normal(x.values)
            pc = runs_chebyshev(sd, EPS_RESULT * m)
            pn = runs_clt(sd, EPS_RESULT * m)
            p_needed.append(pc)
            rel.append(comma(100 * sd / m, 1))
            chis.append(comma(chi2, 1))
            runs.append(str(math.ceil(pc)))
            log(f"[pilot] s={s} {r}: mean={m:.3f} sd={sd:.4f} ({sd / m:.2%}) χ²={chi2:.2f} (кр {chi2_crit:.2f}) "
                f"p_Cheb={pc:.1f} p_CLT={pn:.1f}")
        rows.append(f"{s} & " + " & ".join(rel + chis + runs))
    p_max = math.ceil(max(p_needed))
    p = int(math.ceil(p_max / 10) * 10) if p_max % 10 else p_max + 10
    log(f"[pilot] найбільша потрібна кількість прогонів (Чебишов, ε = {EPS_RESULT:.0%}) = {p_max} → p = {p}")
    resp = " & ".join(f"\\textbf{{${RESPONSES[r][0]}$}}" for r in RESPONSES)
    write_table(
        "tab-exp-pilot", "|c|r|r|r|r|r|r|r|r|r|",
        f"Розкид відгуків, критерій $\\chi^2$ і потрібна кількість прогонів ($\\gamma = 0{{,}}7$, 100 прогонів, "
        f"$T_{{\\text{{мод}}}} = 3{{,}}5\\cdot10^6$~хв, $T_{{\\text{{п}}}} = 2\\cdot10^4$~хв, "
        f"$\\varepsilon = 2$~\\% середнього, $\\beta = 0{{,}}95$, $\\chi^2_{{\\text{{кр}}}} = 14{{,}}07$)",
        "tab:exp-pilot",
        " & \\multicolumn{3}{c|}{\\textbf{$\\sigma/\\bar y$, \\%}} & \\multicolumn{3}{c|}{\\textbf{$\\chi^2$}} & "
        "\\multicolumn{3}{c|}{\\textbf{$p$ за~\\eqref{eq:chebyshev}}} \\\\ \\cline{2-10}\n  "
        "\\textbf{$s$} & " + " & ".join([resp] * 3),
        rows)

    # Гістограми W по 100 прогонах для двох стратегій
    fig, axes = plt.subplots(1, 2, figsize=(16.5 / 2.54, 6.2 / 2.54))
    for ax, s, letter in zip(axes, (1, 3), "аб"):
        x = pilot[pilot.s == s].W
        ax.hist(x, bins=12, color=COLORS[s], alpha=0.75, edgecolor="white", linewidth=1.5)
        grid = np.linspace(x.min(), x.max(), 200)
        width = (x.max() - x.min()) / 12
        ax.plot(grid, stats.norm.pdf(grid, x.mean(), x.std(ddof=1)) * len(x) * width,
                color="#333333", lw=1.2, ls="--")
        ax.set_xlabel("$W$, хв")
        ax.set_ylabel("Кількість прогонів")
        ax.set_title(f"{letter}) $s = {s}$", loc="left")
        ax.xaxis.set_major_formatter(axis_comma())
        ax.grid(axis="x", visible=False)
    fig.tight_layout()
    save(fig, "exp-hist")
    return p


# ----------------------------------------------------------------------------------------
# 3. Факторний експеримент: середні, ANOVA, регресія
# ----------------------------------------------------------------------------------------

def cell_stats(fac: pd.DataFrame) -> pd.DataFrame:
    agg = []
    for (s, g), grp in fac.groupby(["s", "gamma"]):
        rec = {"s": s, "gamma": g, "p": len(grp)}
        for col in ["W", "Lq", "Wq", "W_A", "W_B", "W_C", "Wq_A", "Wq_B", "Wq_C", "U", "reserved_idle"]:
            x = grp[col]
            rec[col] = x.mean()
            rec[col + "_sd"] = x.std(ddof=1)
            rec[col + "_ci"] = stats.t.ppf(1 - ALPHA / 2, len(x) - 1) * x.std(ddof=1) / math.sqrt(len(x))
            # Точність за нерівністю Чебишова для p прогонів: ε = σ / sqrt(p (1 − β))
            rec[col + "_eps_cheb"] = x.std(ddof=1) / math.sqrt(len(x) * (1 - BETA))
        agg.append(rec)
    return pd.DataFrame(agg)


def factorial_tables(cells: pd.DataFrame, p: int):
    gammas = sorted(cells.gamma.unique())
    for r, (sym, unit) in RESPONSES.items():
        rows = []
        for g in gammas:
            line = [comma(g, 2), lambda_sum(g)]
            for s in range(1, 6):
                c = cells[(cells.s == s) & (cells.gamma == g)].iloc[0]
                # Середнє округлено до розряду першої значущої цифри половини інтервалу (не менше
                # двох знаків): за малих L_q два знаки дали б безінформативне «± 0,00»
                digits = max(2, -Decimal(f"{c[r + '_ci']:.1g}").as_tuple().exponent)
                line.append(f"{comma(c[r], digits)}$\\pm${comma(c[r + '_ci'], digits)}")
            rows.append(" & ".join(line))
        write_table(
            f"tab-exp-{r.lower()}", "|c|c|r|r|r|r|r|",
            f"Середнє значення ${sym}$, {unit}, і половина довірчого інтервалу ($\\beta = 0{{,}}95$, "
            f"{p} прогонів, $T_{{\\text{{мод}}}} = 3{{,}}5\\cdot10^6$~хв, $T_{{\\text{{п}}}} = 2\\cdot10^4$~хв)",
            f"tab:exp-{r.lower()}",
            "\\textbf{$\\gamma$} & \\textbf{\\begin{tabular}{@{}c@{}}$\\lambda_\\Sigma$,\\\\ завд./хв\\end{tabular}} & "
            + " & ".join(f"\\textbf{{$s = {s}$}}" for s in range(1, 6)),
            rows, colsep="2.5pt")
    eps_rel = max((cells[r + "_eps_cheb"] / cells[r]).max() for r in RESPONSES)
    ci_rel = max((cells[r + "_ci"] / cells[r]).max() for r in RESPONSES)
    log(f"[factorial] досягнута точність: Чебишов ε/ȳ ≤ {eps_rel:.2%}, ДІ (Стьюдент) ≤ {ci_rel:.2%}")

    # Додаток: за типами завдань
    rows = []
    for _, c in cells.iterrows():
        rows.append(f"{int(c.s)} & {comma(c.gamma, 2)} & " + " & ".join(
            comma(c[k], 2) for k in ["W_A", "W_B", "W_C", "Wq_A", "Wq_B", "Wq_C"])
            + f" & {comma(c.U, 3)} & {comma(c.reserved_idle, 3)}")
    write_table(
        "tab-exp-bytype", "|c|c|r|r|r|r|r|r|r|r|",
        f"Відгуки за типами завдань (середнє за {p} прогонами, $T_{{\\text{{мод}}}} = 3{{,}}5\\cdot10^6$~хв, "
        f"$T_{{\\text{{п}}}} = 2\\cdot10^4$~хв)",
        "tab:exp-bytype",
        "\\textbf{$s$} & \\textbf{$\\gamma$} & \\textbf{$W_A$, хв} & \\textbf{$W_B$, хв} & \\textbf{$W_C$, хв} & "
        "\\textbf{$W_{q,A}$, хв} & \\textbf{$W_{q,B}$, хв} & \\textbf{$W_{q,C}$, хв} & \\textbf{$U$} & "
        "\\textbf{$\\bar m_{\\text{RC}}/M$}",
        rows)


def cochran_critical(k: int, f: int) -> float:
    """Критичне значення критерію Кохрена (α = 0,05) для k дисперсій з f ступенями вільності:
    G_кр = F / (F + k − 1), F — квантиль розподілу Фішера рівня 1 − α/k зі ступенями (f; (k − 1) f)."""
    F = stats.f.ppf(1 - ALPHA / k, f, (k - 1) * f)
    return F / (F + k - 1)


def cochran_g(data: pd.DataFrame, response: str) -> tuple[float, float, pd.Series]:
    """Критерій Кохрена G = max s_j² / Σ s_j² за точками (s, γ) набору data і його критичне значення."""
    groups = data.groupby(["s", "gamma"])[response]
    v = groups.var()
    return v.max() / v.sum(), cochran_critical(len(v), int(groups.size().min()) - 1), v


def cochran_analysis(fac: pd.DataFrame):
    """Перевірка відтворюваності (однорідності дисперсій точок плану) за критерієм Кохрена і
    перевірка засобів, які пропонує лекція Т4.2 за невідтворюваного експерименту: звуження
    інтервалу варіювання фактора γ (до двох рівнів або одного) і зміна кількості прогонів p."""
    levels = sorted(fac.gamma.unique())
    low, high = levels[:2], levels[-2:]
    variants = [
        ("full", fac),
        ("gamma_low", fac[fac.gamma.isin(low)]),
        ("gamma_high", fac[fac.gamma.isin(high)]),
        ("p20", fac[fac.rep <= 20]),
    ]
    rows, records = [], []
    crit = {}
    for r, (sym, unit) in RESPONSES.items():
        cells_ = []
        for name, data in variants:
            g_val, g_crit, v = cochran_g(data, r)
            crit[name] = g_crit
            records.append({"response": r, "variant": name, "G": g_val, "G_crit": g_crit,
                            "var_ratio": v.max() / v.min(), "argmax": v.idxmax()})
            log(f"[Cochran] {r} ({name}): G = {g_val:.4f} (G_кр = {g_crit:.4f}, k = {len(v)}), "
                f"max/min s² = {v.max() / v.min():.1f}, max у точці {v.idxmax()}")
            cells_.append(comma(g_val, 3))
            if name == "full":
                single = [cochran_g(fac[fac.gamma == g], r) for g in levels]
                crit["single"] = single[0][1]
                g_single = [g for g, _, _ in single]
                ratios = [vv.max() / vv.min() for _, _, vv in single]
                for g, (gv, gc, _), rt in zip(levels, single, ratios):
                    records.append({"response": r, "variant": f"gamma={g}", "G": gv, "G_crit": gc,
                                    "var_ratio": rt})
                log(f"[Cochran] {r} (один рівень γ): G = {min(g_single):.4f}…{max(g_single):.4f} "
                    f"(G_кр = {crit['single']:.4f}, k = 5), max/min s² = {min(ratios):.1f}…{max(ratios):.1f}")
        cells_.insert(3, f"{comma(min(g_single), 3)}--{comma(max(g_single), 3)}")
        rows.append(f"${sym}$ & " + " & ".join(cells_))
    for p_ in (10, 40):
        for r in RESPONSES:
            g_val, g_crit, _ = cochran_g(fac[fac.rep <= p_], r)
            log(f"[Cochran] {r} (p = {p_}): G = {g_val:.4f} (G_кр = {g_crit:.4f})")
    pd.DataFrame(records).to_csv(OUT / "cochran.csv", index=False)
    gc = {k: comma(v, 3) for k, v in crit.items()}
    rows.append(f"$G_{{\\text{{кр}}}}$ & {gc['full']} & {gc['gamma_low']} & {gc['gamma_high']} & "
                f"{gc['single']} & {gc['p20']}")
    num = lambda x: comma(x, 2).replace(",", "{,}")
    g_lbl = lambda gs: f"$\\gamma \\in \\{{{num(gs[0])};\\,{num(gs[1])}\\}}$"
    write_table(
        "tab-exp-cochran", "|c|c|c|c|c|c|",
        f"Перевірка відтворюваності за критерієм Кохрена ($\\alpha = 0{{,}}05$; повний план -- "
        f"{fac.groupby(['s', 'gamma']).ngroups}~точок по {int(fac.groupby(['s', 'gamma']).size().min())}~прогонів)",
        "tab:exp-cochran",
        "\\textbf{Відгук} & \\textbf{Повний план} & \\textbf{" + g_lbl(low) + "} & \\textbf{"
        + g_lbl(high) + "} & \\textbf{Один рівень $\\gamma$} & \\textbf{$p = 20$}",
        rows, colsep="2pt")


def anova_analysis(fac: pd.DataFrame):
    """Дисперсійний аналіз впливу стратегії (якісний фактор) за формулами 3.10–3.11 посібника."""
    # Однофакторний аналіз впливу стратегії окремо на кожному рівні γ
    rows, records = [], []
    for g in sorted(fac.gamma.unique()):
        line = [comma(g, 2)]
        for r in RESPONSES:
            sub = fac[fac.gamma == g]
            q = sub.s.nunique()
            pp = sub.groupby("s").size().min()
            means = sub.groupby("s")[r].mean()
            grand = sub[r].mean()
            ss_f = pp * ((means - grand) ** 2).sum()
            ss_r = ((sub[r] - sub.s.map(means)) ** 2).sum()
            d_f = ss_f / (q - 1)
            d_r = ss_r / (q * (pp - 1))
            f = d_f / d_r
            fcrit = stats.f.ppf(1 - ALPHA, q - 1, q * (pp - 1))
            records.append({"gamma": g, "response": r, "D_f": d_f, "D_r": d_r, "F": f, "F_crit": fcrit})
            line.append(comma(f, 1))
        rows.append(" & ".join(line) + f" & {comma(fcrit, 2)}")
    pd.DataFrame(records).to_csv(OUT / "anova_one_way.csv", index=False)
    write_table(
        "tab-exp-anova1", "|c|r|r|r|c|",
        "Значення критерію Фішера для впливу стратегії на кожному рівні $\\gamma$ ($\\alpha = 0{,}05$)",
        "tab:exp-anova1",
        "\\textbf{$\\gamma$} & \\textbf{$F$ для $W$} & \\textbf{$F$ для $L_q$} & \\textbf{$F$ для $W_q$} & "
        "\\textbf{$F_{\\text{кр}}$}",
        rows)

    # Порівняння близьких стратегій попарно: однофакторний аналіз із двома рівнями. Критерій
    # Тьюкі тут непридатний: він використовує спільну залишкову дисперсію всіх п'яти стратегій,
    # а розкид у стратегіях 3-5 у кілька разів більший, ніж у 1-2.
    pairs = [(1, 2), (3, 5), (4, 5), (4, 3)]
    rows, records = [], []
    fcrit = None
    for g in sorted(fac.gamma.unique()):
        sub = fac[fac.gamma == g]
        line = [comma(g, 2)]
        for a_, b_ in pairs:
            x, y = sub[sub.s == a_].W, sub[sub.s == b_].W
            f, pv = stats.f_oneway(x, y)
            fcrit = stats.f.ppf(1 - ALPHA, 1, len(x) + len(y) - 2)
            d = 100 * (y.mean() - x.mean()) / x.mean()
            records.append({"gamma": g, "pair": f"{a_}-{b_}", "delta_pct": d, "F": f, "p_value": pv,
                            "F_crit": fcrit})
            mark = "" if f > fcrit else "$^{*}$"
            line.append(f"{comma(d, 2)} & {comma(f, 1)}{mark}")
        rows.append(" & ".join(line))
    pd.DataFrame(records).to_csv(OUT / "pairs.csv", index=False)
    for r in records:
        log(f"[pairs] γ={r['gamma']} {r['pair']}: Δ={r['delta_pct']:+.2f}% F={r['F']:.1f} p={r['p_value']:.3f}")
    log(f"[pairs] F_кр(1; {2 * fac.groupby(['s', 'gamma']).size().min() - 2}) = {fcrit:.2f}")
    write_table(
        "tab-exp-pairs", "|c|r|r|r|r|r|r|r|r|",
        "Попарне порівняння стратегій за середнім часом перебування: відносна різниця "
        "$\\Delta = (\\bar W_j - \\bar W_i)/\\bar W_i$ і критерій Фішера "
        f"($F_{{\\text{{кр}}}} = {comma(fcrit, 2).replace(',', '{,}')}$, $^{{*}}$ -- різниця незначуща)",
        "tab:exp-pairs",
        "\\textbf{$\\gamma$} & " + " & ".join(
            f"\\textbf{{$\\Delta_{{{a_}\\to{b_}}}$, \\%}} & \\textbf{{$F$}}" for a_, b_ in pairs),
        rows)


REG_DEGREE = 3   # повна модель: y = b0 + b1·x + b2·x² + b3·x^3


def z_of(gamma):
    """Функція фактора: z = 1/(1 − γ·ρ0) — черги зростають пропорційно 1/(1 − ρ)."""
    return 1 / (1 - np.asarray(gamma) * RHO0)


def fit_ls(X: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Метод найменших квадратів за формулою (3.9): b = (X^T X)^(-1) X^T Y; повертає b і діагональ (X^T X)^(-1)."""
    c = np.linalg.inv(X.T @ X)
    return c @ X.T @ y, np.diag(c)


def regression_analysis(cells: pd.DataFrame):
    """Регресія відгуку від γ для кожної стратегії за середніми значеннями N = 7 експериментів.

    Фактор — z = 1/(1 − γ·ρ0), нормалізований до [−1; 1] за формулою (3.6) посібника:
    x = (z − z0)/Δz. Повна модель (формула 3.7) — поліном 3-го степеня від x. Далі процедура
    лекції Т4.2: значущість коефіцієнтів за критерієм Стьюдента (t_i = |b_i|/s_bi,
    s_bi² = c_ii s²_відтв/p, s²_відтв — середня дисперсія точок), відкидання незначущих доданків,
    адекватність отриманого рівняння за критерієм Фішера (F = s²_ад/s²_відтв,
    s²_ад = p Σ(ȳ_j − y^_j)²/(N − d)). Для порівняння — колишні моделі: y = b0 + b1 z + b2 z²
    квадратичний і кубічний поліноми від нормалізованого γ.
    """
    gammas = np.sort(cells.gamma.unique())
    z = z_of(gammas)
    z0, dz = (z.max() + z.min()) / 2, (z.max() - z.min()) / 2
    x = (z - z0) / dz
    n_pts = len(gammas)
    p = int(cells.p.min())
    f_rep = n_pts * (p - 1)
    t_crit = stats.t.ppf(1 - ALPHA / 2, f_rep)
    powers = np.column_stack([x ** i for i in range(REG_DEGREE + 1)])
    log(f"[regr] z in [{z.min():.3f}; {z.max():.3f}], z0 = {z0:.4f}, Δz = {dz:.4f}; x = {np.round(x, 3)}; "
        f"t_кр({f_rep}) = {t_crit:.3f}")

    def check(X, y, s2):
        b, c = fit_ls(X, y)
        s2_rep = s2.mean()
        t = np.abs(b) / np.sqrt(c * s2_rep / p)
        fit = X @ b
        d = X.shape[1]
        f_ad = p * ((y - fit) ** 2).sum() / (n_pts - d) / s2_rep
        f_cr = stats.f.ppf(1 - ALPHA, n_pts - d, f_rep)
        R = math.sqrt(1 - ((y - fit) ** 2).sum() / ((y - y.mean()) ** 2).sum())
        return b, t, f_ad, f_cr, R, (np.abs(y - fit) / y).max()

    records = []
    for r in RESPONSES:
        for s in range(1, 6):
            c = cells[cells.s == s].sort_values("gamma")
            y, s2 = c[r].values, c[r + "_sd"].values ** 2
            b_full, t_full, f_full, _, _, _ = check(powers, y, s2)
            keep = [i for i in range(REG_DEGREE + 1) if t_full[i] >= t_crit]
            b, t, f_ad, f_cr, R, dmax = check(powers[:, keep], y, s2)
            assert (t >= t_crit).all(), f"{r} s={s}: після відкидання лишилися незначущі коефіцієнти"
            # Колишні моделі для порівняння
            _, _, f_q, f_q_cr, _, d_q = check(np.column_stack([np.ones(n_pts), z, z ** 2]), y, s2)
            xg = (gammas - 0.55) / 0.15
            _, _, _, _, _, d_g = check(np.column_stack([np.ones(n_pts), xg, xg ** 2]), y, s2)
            _, _, _, _, _, d_g3 = check(np.column_stack([np.ones(n_pts), xg, xg ** 2, xg ** 3]), y, s2)
            rec = {"response": r, "s": s, "terms": "".join(str(i) for i in keep), "F": f_ad, "F_crit": f_cr,
                   "adequate": f_ad < f_cr, "R": R, "max_rel_err": dmax, "F_full": f_full,
                   "F_quad_z": f_q, "F_quad_z_crit": f_q_cr, "max_rel_err_quad_z": d_q,
                   "max_rel_err_poly_gamma": d_g, "max_rel_err_poly3_gamma": d_g3}
            for i in range(REG_DEGREE + 1):
                rec[f"t{i}"] = t_full[i]
                rec[f"b{i}"] = b[keep.index(i)] if i in keep else 0.0
            records.append(rec)
            log(f"[regr] {r} s={s}: t = {np.round(t_full, 1)}, доданки {keep}, b = {np.round(b, 3)}, "
                f"F = {f_ad:.2f} (кр {f_cr:.2f}), R = {R:.5f}, δ = {dmax:.2%}; z,z²: F = {f_q:.2f} "
                f"(кр {f_q_cr:.2f}), δ = {d_q:.2%}")
    reg = pd.DataFrame(records)
    reg.to_csv(OUT / "regression.csv", index=False)
    log(f"[regr] відібрані рівняння: F ≤ {reg.F.max():.2f}, R ≥ {reg.R.min():.5f}, δ_max ≤ {reg.max_rel_err.max():.2%}; "
        f"неадекватних: {(~reg.adequate).sum()}")
    bad_q = reg[reg.F_quad_z >= reg.F_quad_z_crit]
    log(f"[regr] z, z²: неадекватні {[(row.response, row.s) for row in bad_q.itertuples()]}, "
        f"F = {bad_q.F_quad_z.min():.2f}…{bad_q.F_quad_z.max():.2f}; δ_max ≤ {reg.max_rel_err_quad_z.max():.2%}")
    log(f"[regr] поліном від нормалізованого γ: квадратичний δ_max ≤ {reg.max_rel_err_poly_gamma.max():.2%}, "
        f"кубічний δ_max ≤ {reg.max_rel_err_poly3_gamma.max():.2%}")
    # Порівняння нормалізованих коефіцієнтів: чутливість стратегій 3-5 відносно 1-2
    for r in RESPONSES:
        sub = reg[reg.response == r].set_index("s")
        for i in (1, 2):
            ratios = [sub.loc[a, f"b{i}"] / sub.loc[b_, f"b{i}"] for a in (3, 4, 5) for b_ in (1, 2)]
            log(f"[regr] {r}: b{i}(s=3..5)/b{i}(s=1..2) = {min(ratios):.2f}…{max(ratios):.2f}")
        lin = sub.b1 / sub.b2.abs()
        log(f"[regr] {r}: b1/|b2| = {lin.min():.1f}…{lin.max():.1f}")

    # Таблиця для розділу 4: t-значення повної моделі, F відібраного рівняння
    rows = []
    for row in reg.itertuples():
        sym = RESPONSES[row.response][0]
        ts = [comma(getattr(row, f"t{i}"), 1) + ("" if str(i) in row.terms else "$^{*}$")
              for i in range(REG_DEGREE + 1)]
        rows.append(f"${sym}$ & {row.s} & " + " & ".join(ts)
                    + f" & {comma(row.F, 2)} & {comma(row.F_crit, 2)} & {'так' if row.adequate else 'ні'}")
    write_table(
        "tab-exp-regcheck", "|c|c|r|r|r|r|r|r|c|",
        f"Значущість коефіцієнтів рівняння~\\eqref{{eq:regression}} за критерієм Стьюдента "
        f"($t_{{\\text{{кр}}}} = {comma(t_crit, 2).replace(',', '{,}')}$, $^{{*}}$ -- коефіцієнт незначущий) "
        f"і адекватність рівняння після відкидання незначущих доданків за критерієм Фішера ($\\alpha = 0{{,}}05$)",
        "tab:exp-regcheck",
        "\\textbf{Відгук} & \\textbf{$s$} & \\textbf{$t_0$} & \\textbf{$t_1$} & \\textbf{$t_2$} & "
        "\\textbf{$t_3$} & \\textbf{$F$} & \\textbf{$F_{\\text{кр}}$} & \\textbf{Адекватне}",
        rows)

    # Таблиця для додатка: коефіцієнти в нормалізованому вигляді
    rows = []
    for row in reg.itertuples():
        sym = RESPONSES[row.response][0]
        bs = [comma(getattr(row, f"b{i}"), 3) if str(i) in row.terms else "--" for i in range(REG_DEGREE + 1)]
        rows.append(f"${sym}$ & {row.s} & " + " & ".join(bs)
                    + f" & {comma(row.R, 5)} & {comma(100 * row.max_rel_err, 2)}")
    write_table(
        "tab-exp-regression", "|c|c|r|r|r|r|r|r|",
        "Коефіцієнти рівняння регресії~\\eqref{eq:regression} для нормалізованого фактора~$x$ "
        "(-- доданок відкинуто), індекс кореляції та найбільша відносна похибка",
        "tab:exp-regression",
        "\\textbf{Відгук} & \\textbf{$s$} & \\textbf{$b_0$} & \\textbf{$b_1$} & \\textbf{$b_2$} & "
        "\\textbf{$b_3$} & \\textbf{$R$} & \\textbf{$\\delta_{\\max}$, \\%}",
        rows)
    return reg, (z0, dz)


def response_figures(cells: pd.DataFrame, reg: pd.DataFrame, norm: tuple[float, float]):
    grid = np.linspace(0.4, 0.7, 121)
    xg = (z_of(grid) - norm[0]) / norm[1]
    for r, (sym, unit) in RESPONSES.items():
        fig, ax = plt.subplots(figsize=(16.5 / 2.54, 8.0 / 2.54))
        for s in range(1, 6):
            c = cells[cells.s == s].sort_values("gamma")
            row = reg[(reg.response == r) & (reg.s == s)].iloc[0]
            yg = sum(row[f"b{i}"] * xg ** i for i in range(REG_DEGREE + 1))
            ax.plot(grid, yg, color=COLORS[s], ls=LINESTYLES[s], lw=1.3)
            ax.errorbar(c.gamma, c[r], yerr=c[r + "_ci"], fmt=MARKERS[s], color=COLORS[s], ms=6,
                        mec="white", mew=0.8, capsize=2, lw=0, elinewidth=1,
                        label=f"$s = {s}$ ({ORDERING[s]})")
        ax.set_xlabel("Коефіцієнт інтенсивності $\\gamma$")
        ax.set_ylabel(f"${sym}$, {unit}")
        ax.set_xlim(0.385, 0.715)
        ax.xaxis.set_major_formatter(axis_comma(2))
        ax.yaxis.set_major_formatter(axis_comma())
        add_lambda_axis(ax, 0.02)
        ax.legend(loc="upper left", frameon=False)
        save(fig, f"exp-{r.lower()}")


# ----------------------------------------------------------------------------------------
# 4. Пробний запуск пропозиції (розділ 5): обсяг пам'яті M = 3
# ----------------------------------------------------------------------------------------

M_IMPROVED = 3
RHO0_IMPROVED = 2.35 / M_IMPROVED   # Σ λ_i^0 v_i / μ_i = 0,8 + 0,35 + 1,2 = 2,35 (формула 1.1)


def improvement_analysis(cells2: pd.DataFrame):
    imp = pd.read_csv(RESULTS / "improvement.csv")
    assert (imp["M"] == M_IMPROVED).all() and (imp.T_mod == T_MOD).all() and (imp.T_w == T_WARMUP).all()
    cells3 = cell_stats(imp)
    cells3.to_csv(OUT / "improvement_means.csv", index=False)
    p3 = int(imp.groupby(["s", "gamma"]).size().min())

    # Чи достатні T_мод і T_п з розділу 4: критерій (3.1) для γ = 1
    traj = pd.read_csv(RESULTS / "trajectories_improved.csv")
    t_crit = max(criterion_time(grp, r, EPS_TRANSIENT)[0] for _, grp in traj.groupby("s") for r in RESPONSES)
    eps_rel = max((cells3[r + "_eps_cheb"] / cells3[r]).max() for r in RESPONSES)
    log(f"[M=3] ρ^0 = {RHO0_IMPROVED:.4f}; критерій (3.1) за γ = 1: найбільший T = {t_crit:.0f} хв "
        f"(T_мод = {T_MOD}); точність (Чебишов, {p3} прогонів) ε/ȳ ≤ {eps_rel:.2%}")

    # Таблиця: M = 3 за умови задачі (γ = 1)
    rows = []
    for s in range(1, 6):
        c = cells3[(cells3.s == s) & np.isclose(cells3.gamma, 1.0)].iloc[0]
        rows.append(f"{s} & " + " & ".join(f"{comma(c[r], 2)} $\\pm$ {comma(c[r + '_ci'], 2)}" for r in RESPONSES)
                    + " & " + " & ".join(comma(c[k], 1) for k in ["W_A", "W_B", "W_C"]) + f" & {comma(c.U, 3)}")
        log(f"[M=3] γ=1 s={s}: W={c.W:.2f} Lq={c.Lq:.3f} Wq={c.Wq:.2f} W_A={c.W_A:.2f} W_B={c.W_B:.2f} "
            f"W_C={c.W_C:.2f} U={c.U:.3f} RC={c.reserved_idle:.4f}")
    write_table(
        "tab-imp-base", "|c|r|r|r|r|r|r|r|",
        f"Відгуки системи з памʼяттю $M = 3$ за інтенсивностей з умови задачі ($\\gamma = 1$, {p3} прогонів, "
        f"$T_{{\\text{{мод}}}} = 3{{,}}5\\cdot10^6$~хв, $T_{{\\text{{п}}}} = 2\\cdot10^4$~хв)",
        "tab:imp-base",
        "\\textbf{$s$} & \\textbf{$W$, хв} & \\textbf{$L_q$, завд.} & \\textbf{$W_q$, хв} & "
        "\\textbf{$W_A$, хв} & \\textbf{$W_B$, хв} & \\textbf{$W_C$, хв} & \\textbf{$U$}",
        rows)

    # Таблиця: порівняння M = 2 і M = 3 за γ = 0,7
    rows = []
    for s in range(1, 6):
        c2 = cells2[(cells2.s == s) & np.isclose(cells2.gamma, 0.7)].iloc[0]
        c3 = cells3[(cells3.s == s) & np.isclose(cells3.gamma, 0.7)].iloc[0]
        cells_ = []
        for r in list(RESPONSES) + ["W_C"]:
            d = 100 * (c3[r] - c2[r]) / c2[r]
            cells_ += [comma(c2[r], 2), comma(c3[r], 2), comma(d, 0)]
            log(f"[M=3] γ=0.7 s={s} {r}: M=2 {c2[r]:.3f} → M=3 {c3[r]:.3f} ({d:+.1f}%)")
        rows.append(f"{s} & " + " & ".join(cells_[:9]))
    write_table(
        "tab-imp-compare", "|c|r|r|r|r|r|r|r|r|r|",
        f"Порівняння відгуків за обсягу памʼяті $M = 2$ і $M = 3$ ($\\gamma = 0{{,}}7$, {p3} прогонів, "
        f"$T_{{\\text{{мод}}}} = 3{{,}}5\\cdot10^6$~хв, $T_{{\\text{{п}}}} = 2\\cdot10^4$~хв)",
        "tab:imp-compare",
        " & " + " & ".join(f"\\multicolumn{{3}}{{c|}}{{\\textbf{{${RESPONSES[r][0]}$, {RESPONSES[r][1]}}}}}"
                           for r in RESPONSES)
        + " \\\\ \\cline{2-10}\n  \\textbf{$s$} & "
        + " & ".join(["\\textbf{$M = 2$} & \\textbf{$M = 3$} & \\textbf{$\\Delta$, \\%}"] * 3),
        rows)

    # Рисунок: W(γ) за M = 2 (розділ 4) і M = 3
    fig, ax = plt.subplots(figsize=(16.5 / 2.54, 8.0 / 2.54))
    for s in range(1, 6):
        c2 = cells2[cells2.s == s].sort_values("gamma")
        c3 = cells3[cells3.s == s].sort_values("gamma")
        ax.plot(c2.gamma, c2.W, color=COLORS[s], ls=":", lw=1.1, marker=MARKERS[s], ms=4, mfc="white")
        ax.plot(c3.gamma, c3.W, color=COLORS[s], ls="-", lw=1.4, marker=MARKERS[s], ms=5, mec="white",
                mew=0.8, label=f"$s = {s}$")
    ax.axvline(0.7, color="#999999", lw=0.8, ls="--")
    ax.set_xlabel("Коефіцієнт інтенсивності $\\gamma$")
    ax.set_ylabel("$W$, хв")
    ax.set_xlim(0.38, 1.02)
    ax.xaxis.set_major_formatter(axis_comma(1))
    ax.yaxis.set_major_formatter(axis_comma())
    add_lambda_axis(ax, 0.05)
    ax.legend(loc="upper left", frameon=False)
    ax.annotate("$M = 2$ (пунктир)", (0.66, 58), ha="right", fontsize=11, color="#555555")
    ax.annotate("$M = 3$ (суцільні лінії)", (0.99, 26), ha="right", fontsize=11, color="#555555")
    save(fig, "imp-w")


# ----------------------------------------------------------------------------------------
# 5. Межа області стійкості (γ = 0,75 і 0,8) і зміна співвідношення потоків (лише λ_C)
# ----------------------------------------------------------------------------------------

BOUNDARY_MOMENTS = {0.75: (2e6, 8e6), 0.8: (2e6, 4e6, 8e6)}


def boundary_analysis():
    """Чи стійка система за рівнів γ понад план: середня черга на [0; t] для зростаючих t
    (5 прогонів до 8·10^6 хв) і коефіцієнт використання пам'яті U порівняно з ρ = γ·ρ0 (10 прогонів)."""
    traj = pd.read_csv(RESULTS / "trajectories_boundary.csv")
    runs = pd.read_csv(RESULTS / "boundary.csv")
    assert (runs.T_mod == T_MOD).all() and (runs.T_w == T_WARMUP).all()
    lq = traj.groupby(["gamma", "s", "t"]).Lq.mean()
    u = runs.groupby(["gamma", "s"])[["U", "reserved_idle", "Lq"]].mean()
    n_traj, n_runs = traj.run.nunique(), int(runs.groupby(["gamma", "s"]).size().min())
    rows = []
    for s in range(1, 6):
        cells_ = [str(s)]
        for g, moments in BOUNDARY_MOMENTS.items():
            cells_ += [comma(lq.loc[(g, s, t)], 1) for t in moments] + [comma(u.loc[(g, s), "U"], 3)]
        rows.append(" & ".join(cells_))
    for (g, s), row in u.iterrows():
        series = [lq.loc[(g, s, t)] for t in (1e6, 2e6, 4e6, 8e6)]
        log(f"[межа] γ={g} s={s}: ρ = {g * RHO0:.4f}, U = {row.U:.4f}, m_RC/M = {row.reserved_idle:.4f}, "
            f"1 − U = {1 - row.U:.4f}; L_q на [0; t], t = 1/2/4/8·10^6: "
            + " / ".join(f"{v:.1f}" for v in series))
    # Критерій (3.1) за γ = 0,75 — чи достатньо T_мод, якби цей рівень увійшов до плану
    for s, grp in traj[np.isclose(traj.gamma, 0.75)].groupby("s"):
        ts = {r: criterion_time(grp, r, EPS_TRANSIENT)[0] for r in RESPONSES}
        log(f"[межа] γ=0,75 s={s}: критерій (3.1), ε = 10 %: " + ", ".join(f"{r} {t:.3g}" for r, t in ts.items()))
    head = " & ".join(
        f"\\multicolumn{{{len(m) + 1}}}{{c|}}{{\\textbf{{$\\gamma = {comma(g, 2).replace(',', '{,}')}$ "
        f"($\\lambda_\\Sigma = {lambda_sum(g).replace(',', '{,}')}$~завд./хв)}}}}"
        for g, m in BOUNDARY_MOMENTS.items())
    sub = " & ".join(" & ".join(f"\\textbf{{$t = {comma(t / 1e6, 0)}\\cdot10^6$}}" for t in m)
                     + " & \\textbf{$U$}" for m in BOUNDARY_MOMENTS.values())
    write_table(
        "tab-exp-boundary", "|c|r|r|r|r|r|r|r|",
        f"Середня кількість завдань у черзі $\\bar L_q$ на інтервалі $[0;\\,t]$, завд., "
        f"({n_traj}~прогонів, $t$ -- у хв) і коефіцієнт використання памʼяті $U$ ({n_runs}~прогонів, "
        f"$T_{{\\text{{мод}}}} = 3{{,}}5\\cdot10^6$~хв, $T_{{\\text{{п}}}} = 2\\cdot10^4$~хв) за рівнів $\\gamma$ понад план експерименту",
        "tab:exp-boundary",
        " & " + head + " \\\\ \\cline{2-8}\n  \\textbf{$s$} & " + sub,
        rows)


def flow_analysis(cells: pd.DataFrame):
    """Додаткова серія: λ_A, λ_B — за γ = 0,6, λ_C = γ_C·0,6·λ_C^0. Кількість прогонів — за
    нерівністю Чебишова (4.2) з пілотної вибірки (перші 60 прогонів кожної точки)."""
    flows = pd.read_csv(RESULTS / "flows.csv")
    assert (flows.T_mod == T_MOD).all() and (flows.T_w == T_WARMUP).all()
    base_gamma = float(flows.gamma.iloc[0])
    pilot = flows[flows.rep <= 60]
    need = max(runs_chebyshev(g[r].std(ddof=1), EPS_RESULT * g[r].mean())
               for _, g in pilot.groupby(["s", "gamma_C"]) for r in RESPONSES)
    fc = cell_stats(flows.assign(gamma=flows.gamma_C))
    fc.to_csv(OUT / "flows_means.csv", index=False)
    pf = int(fc.p.min())
    eps_rel = max((fc[r + "_eps_cheb"] / fc[r]).max() for r in RESPONSES)
    log(f"[потік C] γ = {base_gamma}: пілот 60 прогонів → p (Чебишов, ε = 2 %) ≤ {need:.0f}; виконано p = {pf}, "
        f"досягнута точність ε/ȳ ≤ {eps_rel:.2%}")
    lam = {gc: float(flows[flows.gamma_C == gc].lambda_C.iloc[0]) for gc in sorted(flows.gamma_C.unique())}
    rows = []
    for gc, lc in lam.items():
        rho = (base_gamma * (0.2 * 4 + 0.07 * 5) + lc * 10 * 2) / 2
        for s in sorted(fc.s.unique()):
            c = fc[(fc.s == s) & np.isclose(fc.gamma, gc)].iloc[0]
            rows.append(f"{comma(gc, 2 if round(gc * 100) % 10 else 1)} & {comma(lc, 3)} & {comma(rho, 3)} & {s} & "
                        + " & ".join(comma(c[k], 2) for k in ["W", "Lq", "Wq", "W_C"]))
            log(f"[потік C] γ_C={gc} λ_C={lc:.3f} ρ={rho:.4f} U={c.U:.4f} s={s}: W={c.W:.3f} Lq={c.Lq:.3f} "
                f"Wq={c.Wq:.3f} W_A={c.W_A:.2f} W_B={c.W_B:.2f} W_C={c.W_C:.2f} RC={c.reserved_idle:.4f}")
        w1 = fc[(fc.s == 1) & np.isclose(fc.gamma, gc)].iloc[0]
        w4 = fc[(fc.s == 4) & np.isclose(fc.gamma, gc)].iloc[0]
        log(f"[потік C] γ_C={gc}: W(s=4)/W(s=1) = {w4.W / w1.W:.3f}, Lq {w4.Lq / w1.Lq:.3f}, Wq {w4.Wq / w1.Wq:.3f}")
    # Контроль: точка γ_C = 1 збігається з точкою γ = 0,6 факторного плану (інші seed)
    for s in sorted(fc.s.unique()):
        a = fc[(fc.s == s) & np.isclose(fc.gamma, 1.0)].iloc[0]
        b = cells[(cells.s == s) & np.isclose(cells.gamma, base_gamma)].iloc[0]
        log(f"[потік C] контроль s={s}: W = {a.W:.3f} (план γ = {base_gamma}: {b.W:.3f}, різниця "
            f"{100 * (a.W - b.W) / b.W:+.2f} %)")
    write_table(
        "tab-exp-flows", "|c|c|c|c|r|r|r|r|",
        f"Відгуки за зміни лише потоку завдань~C ($\\lambda_A = {comma(base_gamma * 0.2, 2).replace(',', '{,}')}$, "
        f"$\\lambda_B = {comma(base_gamma * 0.07, 3).replace(',', '{,}')}$~завд./хв, {pf}~прогонів, "
        f"$T_{{\\text{{мод}}}} = 3{{,}}5\\cdot10^6$~хв, $T_{{\\text{{п}}}} = 2\\cdot10^4$~хв)",
        "tab:exp-flows",
        "\\textbf{$\\gamma_C$} & \\textbf{$\\lambda_C$, завд./хв} & \\textbf{$\\rho$} & \\textbf{$s$} & "
        "\\textbf{$W$, хв} & \\textbf{$L_q$, завд.} & \\textbf{$W_q$, хв} & \\textbf{$W_C$, хв}",
        rows)


def main():
    OUT.mkdir(exist_ok=True)
    log(f"T_п = {T_WARMUP} хв, T_мод = {T_MOD} хв, ε(3.1) = {EPS_TRANSIENT:.0%}, "
        f"ε результату = {EPS_RESULT:.0%}, β = {BETA}")
    transient_analysis()
    p = pilot_analysis()
    fac = pd.read_csv(RESULTS / "factorial.csv")
    assert (fac.T_mod == T_MOD).all() and (fac.T_w == T_WARMUP).all(), "factorial.csv з іншими T_мод/T_п"
    p_actual = int(fac.groupby(["s", "gamma"]).size().min())
    if p_actual < p:
        log(f"!!! у factorial.csv {p_actual} прогонів у точці, потрібно {p}")
    cells = cell_stats(fac)
    cells.to_csv(OUT / "factorial_means.csv", index=False)
    factorial_tables(cells, p_actual)
    cochran_analysis(fac)
    anova_analysis(fac)
    reg, norm = regression_analysis(cells)
    response_figures(cells, reg, norm)
    improvement_analysis(cells)
    boundary_analysis()
    flow_analysis(cells)
    (OUT / "summary.txt").write_text(summary.getvalue(), encoding="utf-8")


if __name__ == "__main__":
    main()
