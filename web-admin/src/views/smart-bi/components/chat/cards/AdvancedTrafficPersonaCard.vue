<script setup lang="ts">
import { computed } from 'vue';

interface ProviderInfo {
  provider: string;
  bestUse?: string;
  accessMode?: string;
}

interface Recommendation {
  priority?: string;
  action: string;
  why?: string;
}

interface PlainLanguageAnalysis {
  bottomLine: string;
  whatItMeans: string[];
  whyWeThinkSo: string[];
}

interface SolutionStep {
  step: string;
  action: string;
  owner: string;
  expectedOutcome: string;
}

interface DataSufficiency {
  isEnoughForRealDecision: boolean;
  plainVerdict: string;
  why: string[];
  whatCanBeDecidedNow: string[];
  whatCannotBeDecidedYet: string[];
}

interface NeededEvidence {
  name: string;
  whyNeeded: string;
  sourceType: string;
  priority: string;
  publicAvailability: string;
}

interface AdviceRule {
  situation: string;
  plainDiagnosis: string;
  bossAction: string;
  decisionRule: string;
  neededData: string[];
  sourceBasis: string[];
}

interface ExternalSignalStatus {
  source: string;
  keyRequired: boolean;
  envVars: string[];
  status: string;
  refreshCadence: string;
  bestUse: string;
}

interface ExternalSignal {
  type: string;
  source: string;
  severity: string;
  title: string;
  plainImpact: string;
  actionHint: string;
}

interface ExternalSignalPipelineStep {
  source: string;
  productionStatus: string;
  refreshCadence: string;
  storesOneApiCall: boolean;
  whatItWrites: string[];
}

interface ExternalSignalPipeline {
  defaultMode: string;
  whyNotOnPageLoad: string;
  dailyBudgetEnv: Record<string, string>;
  steps: ExternalSignalPipelineStep[];
}

interface ExternalSignals {
  moduleName: string;
  purpose: string;
  sourceStatuses: ExternalSignalStatus[];
  signals: ExternalSignal[];
  collectionPipeline?: ExternalSignalPipeline;
  plainConclusion: string;
  bossActions: string[];
  dataNeededForProduction: string[];
}

interface AdvancedTrafficPersonaData {
  moduleName?: string;
  dataNote?: string;
  enablement?: {
    status?: string;
    scope?: string;
  };
  providers?: ProviderInfo[];
  plainLanguageAnalysis?: PlainLanguageAnalysis;
  solutionPlan?: SolutionStep[];
  dataSufficiency?: DataSufficiency;
  neededEvidence?: NeededEvidence[];
  adviceKnowledgeBase?: AdviceRule[];
  externalSignals?: ExternalSignals;
  analysis?: {
    headline?: string;
    recommendations?: Recommendation[];
  };
}

const props = defineProps<{ data: AdvancedTrafficPersonaData | Record<string, unknown> }>();

const payload = computed(() => props.data as AdvancedTrafficPersonaData);
const providers = computed(() => payload.value.providers ?? []);
const recommendations = computed(() => payload.value.analysis?.recommendations ?? []);
const plainAnalysis = computed(() => payload.value.plainLanguageAnalysis);
const solutionPlan = computed(() => payload.value.solutionPlan ?? []);
const dataSufficiency = computed(() => payload.value.dataSufficiency);
const neededEvidence = computed(() => payload.value.neededEvidence ?? []);
const adviceKnowledgeBase = computed(() => payload.value.adviceKnowledgeBase ?? []);
const externalSignals = computed(() => payload.value.externalSignals);
const statusLabel = computed(() => payload.value.enablement?.status ?? '需额外开通');
</script>

<template>
  <section class="advanced-traffic-card">
    <div class="traffic-header">
      <div>
        <div class="eyebrow">Premium Module</div>
        <h4>{{ payload.moduleName ?? '高级客流画像分析' }}</h4>
      </div>
      <span data-test="premium-status" class="status-chip">{{ statusLabel }}</span>
    </div>

    <p v-if="payload.dataNote" class="data-note">{{ payload.dataNote }}</p>

    <div v-if="plainAnalysis" class="plain-section">
      <div class="section-label">白话结论</div>
      <div class="bottom-line">{{ plainAnalysis.bottomLine }}</div>
      <div class="plain-grid">
        <div>
          <strong>老板现在该怎么理解</strong>
          <ul>
            <li v-for="item in plainAnalysis.whatItMeans" :key="item">{{ item }}</li>
          </ul>
        </div>
        <div>
          <strong>为什么这么判断</strong>
          <ul>
            <li v-for="item in plainAnalysis.whyWeThinkSo" :key="item">{{ item }}</li>
          </ul>
        </div>
      </div>
    </div>

    <div v-if="solutionPlan.length" class="solution-section">
      <div class="section-label">直接方案</div>
      <div class="solution-list">
        <div
          v-for="item in solutionPlan"
          :key="item.step"
          class="solution-row"
        >
          <strong>{{ item.step }}</strong>
          <p>{{ item.action }}</p>
          <span>{{ item.owner }} · {{ item.expectedOutcome }}</span>
        </div>
      </div>
    </div>

    <div v-if="dataSufficiency" class="sufficiency-section">
      <div class="section-label">数据够不够</div>
      <div class="sufficiency-verdict">{{ dataSufficiency.plainVerdict }}</div>
      <div class="plain-grid">
        <div>
          <strong>现在能决定</strong>
          <ul>
            <li v-for="item in dataSufficiency.whatCanBeDecidedNow" :key="item">{{ item }}</li>
          </ul>
        </div>
        <div>
          <strong>现在还不能拍板</strong>
          <ul>
            <li v-for="item in dataSufficiency.whatCannotBeDecidedYet" :key="item">{{ item }}</li>
          </ul>
        </div>
      </div>
      <ul class="why-list">
        <li v-for="item in dataSufficiency.why" :key="item">{{ item }}</li>
      </ul>
    </div>

    <div v-if="neededEvidence.length" class="evidence-section">
      <div class="section-label">还缺哪些资料</div>
      <div
        v-for="item in neededEvidence"
        :key="item.name"
        class="evidence-row"
      >
        <div class="evidence-head">
          <strong>{{ item.name }}</strong>
          <span>{{ item.priority }} · {{ item.sourceType }}</span>
        </div>
        <p>{{ item.whyNeeded }}</p>
        <em>{{ item.publicAvailability }}</em>
      </div>
    </div>

    <div v-if="adviceKnowledgeBase.length" class="knowledge-section">
      <div class="section-label">建议依据库</div>
      <div class="knowledge-list">
        <div
          v-for="item in adviceKnowledgeBase"
          :key="item.situation"
          class="knowledge-row"
        >
          <strong>{{ item.situation }}</strong>
          <p>{{ item.plainDiagnosis }}</p>
          <div class="boss-action">{{ item.bossAction }}</div>
          <small>{{ item.decisionRule }}</small>
        </div>
      </div>
    </div>

    <div v-if="externalSignals" class="external-section">
      <div class="section-label">{{ externalSignals.moduleName }}</div>
      <div class="external-conclusion">{{ externalSignals.plainConclusion }}</div>

      <div class="source-grid">
        <div
          v-for="source in externalSignals.sourceStatuses"
          :key="source.source"
          class="source-cell"
        >
          <div class="source-head">
            <strong>{{ source.source }}</strong>
            <span>{{ source.status }}</span>
          </div>
          <p>{{ source.bestUse }}</p>
          <small>{{ source.keyRequired ? `需要 ${source.envVars.join(' / ')}` : '不需要 Key' }} · {{ source.refreshCadence }}</small>
        </div>
      </div>

      <div class="signal-list">
        <div
          v-for="signal in externalSignals.signals"
          :key="`${signal.type}-${signal.source}`"
          class="signal-row"
        >
          <strong>{{ signal.title }}</strong>
          <p>{{ signal.plainImpact }}</p>
          <small>{{ signal.actionHint }}</small>
        </div>
      </div>

      <div v-if="externalSignals.collectionPipeline" class="pipeline-block">
        <div class="subsection-label">采集链路</div>
        <div class="pipeline-summary">
          <strong>{{ externalSignals.collectionPipeline.defaultMode }}</strong>
          <span>{{ externalSignals.collectionPipeline.whyNotOnPageLoad }}</span>
        </div>
        <div class="budget-line">
          <span
            v-for="(envName, key) in externalSignals.collectionPipeline.dailyBudgetEnv"
            :key="key"
          >
            {{ key }}: {{ envName }}
          </span>
        </div>
        <div class="pipeline-grid">
          <div
            v-for="step in externalSignals.collectionPipeline.steps"
            :key="step.source"
            class="pipeline-cell"
          >
            <div class="source-head">
              <strong>{{ step.source }}</strong>
              <span>{{ step.productionStatus }}</span>
            </div>
            <p>{{ step.refreshCadence }} · {{ step.storesOneApiCall ? '单店一次接口' : '不按门店消耗接口' }}</p>
            <small>{{ step.whatItWrites.join(' / ') }}</small>
          </div>
        </div>
      </div>
    </div>

    <div v-if="providers.length" class="provider-section">
      <div class="section-label">可开通的数据来源</div>
      <div class="provider-grid">
        <div
          v-for="provider in providers"
          :key="provider.provider"
          data-test="provider"
          class="provider-cell"
        >
          <div class="provider-name">{{ provider.provider }}</div>
          <div class="provider-use">{{ provider.bestUse }}</div>
        </div>
      </div>
    </div>

    <div v-if="recommendations.length" class="recommendations">
      <div class="section-label">补充动作</div>
      <div
        v-for="(item, index) in recommendations.slice(0, 3)"
        :key="`${item.priority}-${index}`"
        class="recommendation-row"
      >
        <span class="priority">{{ item.priority ?? 'P1' }}</span>
        <div>
          <strong>{{ item.action }}</strong>
          <p v-if="item.why">{{ item.why }}</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.advanced-traffic-card {
  padding: 14px;
  border: 1px solid #d9e2ec;
  border-radius: 6px;
  background: #fbfdff;
}

.traffic-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.eyebrow {
  font-size: 11px;
  color: #64748b;
  text-transform: uppercase;
}

h4 {
  margin: 2px 0 0;
  color: #1f2937;
  font-size: 16px;
}

.status-chip {
  flex-shrink: 0;
  padding: 4px 8px;
  border-radius: 4px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
  color: #c2410c;
  font-size: 12px;
  font-weight: 600;
}

.data-note {
  margin: 0 0 12px;
  padding: 8px 10px;
  border-left: 3px solid #f59e0b;
  background: #fffbeb;
  color: #78350f;
  font-size: 12px;
  line-height: 1.6;
}

.plain-section,
.solution-section,
.sufficiency-section,
.evidence-section,
.knowledge-section,
.external-section,
.provider-section,
.recommendations {
  margin-top: 12px;
}

.section-label {
  margin-bottom: 8px;
  color: #475569;
  font-size: 12px;
  font-weight: 700;
}

.bottom-line,
.sufficiency-verdict,
.external-conclusion {
  padding: 12px;
  border-radius: 6px;
  background: #eef6ff;
  color: #1e3a8a;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.7;
}

.plain-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
}

.plain-grid > div,
.solution-row,
.evidence-row,
.knowledge-row,
.source-cell,
.signal-row,
.provider-cell {
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #ffffff;
}

.plain-grid strong,
.solution-row strong,
.evidence-head strong,
.knowledge-row strong,
.source-head strong,
.signal-row strong,
.provider-name,
.recommendation-row strong {
  display: block;
  color: #111827;
  font-size: 13px;
  line-height: 1.5;
}

ul {
  margin: 8px 0 0;
  padding-left: 18px;
}

li,
.solution-row p,
.evidence-row p,
.knowledge-row p,
.source-cell p,
.signal-row p,
.provider-use,
.recommendation-row p {
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.solution-list {
  display: grid;
  gap: 8px;
}

.solution-row span,
.evidence-row em {
  color: #334155;
  font-size: 12px;
  font-style: normal;
  font-weight: 600;
  line-height: 1.5;
}

.knowledge-list {
  display: grid;
  gap: 8px;
}

.boss-action {
  margin-top: 8px;
  padding: 8px 10px;
  border-left: 3px solid #0ea5e9;
  background: #f0f9ff;
  color: #0f172a;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.6;
}

.knowledge-row small {
  display: block;
  margin-top: 8px;
  color: #475569;
  font-size: 12px;
  line-height: 1.6;
}

.why-list {
  margin-top: 10px;
}

.evidence-row {
  margin-top: 8px;
}

.evidence-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}

.evidence-head span {
  flex-shrink: 0;
  color: #0369a1;
  font-size: 12px;
  font-weight: 700;
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.source-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
}

.subsection-label {
  margin: 12px 0 8px;
  color: #334155;
  font-size: 12px;
  font-weight: 700;
}

.source-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.source-head span {
  flex-shrink: 0;
  color: #047857;
  font-size: 12px;
  font-weight: 700;
}

.source-cell small,
.signal-row small {
  color: #475569;
  font-size: 12px;
  line-height: 1.6;
}

.signal-list {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}

.pipeline-block {
  margin-top: 10px;
}

.pipeline-summary {
  display: grid;
  gap: 4px;
  padding: 10px;
  border-radius: 6px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.pipeline-summary strong {
  color: #111827;
  font-size: 13px;
}

.pipeline-summary span,
.budget-line span,
.pipeline-cell p,
.pipeline-cell small {
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.budget-line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.budget-line span {
  padding: 4px 8px;
  border-radius: 4px;
  background: #f1f5f9;
}

.pipeline-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
}

.pipeline-cell {
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #ffffff;
}

.recommendation-row {
  display: grid;
  grid-template-columns: 42px 1fr;
  gap: 8px;
  padding: 10px 0;
  border-top: 1px solid #e5e7eb;
}

.priority {
  align-self: start;
  width: 34px;
  padding: 3px 0;
  border-radius: 4px;
  background: #e0f2fe;
  color: #0369a1;
  text-align: center;
  font-size: 11px;
  font-weight: 700;
}

@media (max-width: 900px) {
  .plain-grid,
  .source-grid,
  .pipeline-grid,
  .provider-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .traffic-header,
  .evidence-head {
    flex-direction: column;
  }
}
</style>
