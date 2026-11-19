# Stage 7: Critic Review Report (Feature Specification)

**Target Scope:** `docs/plan_refactorizacion_logica_critica.md` (Lines 1–5: `FeatureRange`)  
**Reviewers:** `egm-design` (Hostile Design Critic), `egm-structural` (Document Architect), `egm-style` (Technical Copy Editor)  
**Standard:** Feature Specification Standard  

---

## Consolidated Findings

### 🔴 Blocker Findings

#### 1. Infeasible & Unmeasurable Objective: "Cero Fallos" in an Reverse-Engineered BLE Hardware Domain
- **Reviewers:** `egm-design`, `egm-style`
- **Affected Scope:** Line 3 (`**Objetivo:**`)
- **Analysis:** Promising a "zero failures" ("cero fallos") standard in an unofficial BLE peripheral client is technically infeasible:
  - RedMagic hardware firmware inherently drops links with `status == 22` (`GATT_CONN_TERMINATE_LOCAL_HOST`), which is an unavoidable hardware limitation documented in [`AGENTS.md`](file:///home/james/Proyectos/RedmagicCooler/AGENTS.md).
  - Android BLE stack drops bursted writes, kernel thermal interfaces (`/sys/class/thermal`) differ across OEMs, and Android background battery managers kill processes.
  - The objective lacks measurable service-level objectives (SLOs), fail-safe default contracts, or defined graceful degradation boundaries.
- **Actionable Remedy:** Refactor the objective to focus on deterministic resilience, graceful degradation, and verifiable fail-safe contracts (e.g., guaranteed bounded reconnection backoff, safe fallback fan speeds on sensor I/O loss, and input sanitization).

#### 2. Missing Problem Statement, Functional Requirements & Constraints Hierarchy
- **Reviewers:** `egm-structural`
- **Affected Scope:** Lines 1–5
- **Analysis:** Lines 1–5 jump directly from a two-line preamble to `## Detailed Implementation Plan` without establishing:
  - **Problem Statement:** Exact failure modes (e.g., FGS SecurityExceptions on API 34+, NaN propagation into fan duty cycles, service-killing race conditions during auto-to-manual switching).
  - **Functional Requirements (FRs):** Deterministic thermal input sanitization, bounded connection state machine, safe sensor outlier filtering.
  - **Non-Functional Requirements (NFRs) & Architectural Constraints:** Local-first zero-internet requirement, single GATT owner op queue preservation, pure JVM unit testability without mocks.
- **Actionable Remedy:** Structure lines 1–5 into formal sections: Problem Statement, Core Requirements, Architectural Constraints, and Out-of-Scope boundaries.

---

### 🟡 Major Findings

#### 3. Ambiguous Compatibility Range ("Android 7-14+") vs. Repository `targetSdk 37`
- **Reviewers:** `egm-design`, `egm-style`
- **Affected Scope:** Line 3
- **Analysis:** Colloquial "Android 7-14+" obscures that `app/build.gradle.kts` targets SDK 37 with `minSdk = 24`. Android 14+ (API 34) and Android 15/16 (API 35–37) impose distinct background execution quotas and FGS restrictions.
- **Actionable Remedy:** Explicitly state the supported range as **API 24 to 37 (Android 7.0 to 15+)** with explicit capability distinctions for BLE permissions and FGS lifecycle.

#### 4. Scope Coupling Across Unequal Testability Layers
- **Reviewers:** `egm-design`
- **Affected Scope:** Line 4 (`**Archivos Objetivo:**`)
- **Analysis:** Combines purely testable domain logic (`ThermalLogic.kt` with 19 JVM unit tests) with Android framework-bound classes (`CoolerService.kt`, `CoolerBleManager.kt`) that currently lack mocking harness support (as documented in `AGENTS.md`). Modifying both simultaneously without a decoupled unit-test strategy risks shipping unverified framework code.
- **Actionable Remedy:** Split the execution into distinct architectural phases: Pure Domain Logic (Fase A) and Android Framework / Hardware Coordination (Fase B).

---

### 🟢 Nit Findings

#### 5. Unsectioned Preamble & Imprecise "Dependencia" Terminology
- **Reviewers:** `egm-structural`, `egm-style`
- **Affected Scope:** Lines 3–4
- **Analysis:** 
  - Metadata keys sit unsectioned between `# H1` and `## H2`.
  - Referring to `AndroidManifest.xml` as a "dependencia" is inaccurate; it is an application manifest configuration file, and its required `connectedDevice` attributes are already implemented.
- **Actionable Remedy:** Format metadata in an introductory section and state `AndroidManifest.xml` as a verification prerequisite.
