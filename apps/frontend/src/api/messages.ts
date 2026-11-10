import type { ProductState } from "./types.ts";

// Only reviewed local copy is ever displayed. Unknown keys fall back to generic copy and
// never echo server text, stack traces or provider responses.
const copy: Record<string, string | null> = {
  "session.expired": "上次发现已过期，已为你打开空白页面，可以重新开始。",
  "session.restored": null,
  "recommendation.waiting": "正在等待推荐名额，轮到你时会自动继续。",
  "recommendation.busy": "当前等待人数较多，请稍后重试。",
  "recommendation.queue_timeout": "本次等待已超时，你可以重新发起推荐。",
  "recommendation.provider_limited": "推荐服务暂时繁忙，请稍后再试。",
  "recommendation.final_exhausted":
    "本次可用候选已用尽，可以修改需求继续发现。",
  "recommendation.personalization_incomplete":
    "本次个性化未完成，先看看这些基础推荐。",
  "execution.interrupted": "本次推荐已中断。",
  QUEUE_FULL: "当前等待人数较多，请稍后重试。",
  QUEUE_TIMEOUT: "本次等待已超时，你可以重新发起推荐。",
  PROVIDER_RATE_LIMITED: "推荐服务暂时繁忙，请稍后再试。",
  EXECUTION_INTERRUPTED: "本次推荐已中断。",
  EXECUTION_GONE: "本次推荐已中断。",
  CATALOG_TIMEOUT: "目录搜索超时，请重试；这不代表没有匹配的专辑。",
  CATALOG_UNAVAILABLE: "目录服务暂时不可用，请稍后重试。",
  CURATED_UNAVAILABLE:
    "暂时无法提供精选推荐，试着补充喜欢的风格、专辑或具体描述。",
  POSITIVE_RECOMMENDATION_NOT_CONFIGURED: "个性化推荐暂未开放，请稍后再试。",
  RECOMMENDATION_UNAVAILABLE: "推荐服务暂时不可用，请稍后再试。",
  DEPENDENCY_UNAVAILABLE: "推荐依赖的服务暂时不可用，请稍后再试。",
  PREFERENCE_UNAVAILABLE: "需求解析暂时不可用，请稍后再试。",
  MAINTENANCE: "服务正在维护，请稍后再试。",
  BUDGET_EXHAUSTED: "本轮推荐额度已用完，可以修改需求重新开始。",
  SESSION_BUDGET_EXHAUSTED: "本轮推荐额度已用完，可以修改需求重新开始。",
  ROUND_BUDGET_EXHAUSTED: "本轮推荐额度已用完，可以修改需求重新开始。",
  UNKNOWN_GENRE: "部分风格已不在目录中，请移除后重试。",
  UNKNOWN_IDENTITY: "部分专辑未能在目录中核验，请移除后重试。",
  INVALID_PAYLOAD: "输入格式有误，请检查后重试。",
  IDEMPOTENCY_CONFLICT: "操作冲突，请刷新状态后再试。",
  RESOURCE_UNAVAILABLE: "该内容暂时无法读取，请刷新状态后重试。",
  LONG_REVIEW_BUSY: "详细乐评生成名额紧张，请稍后点击重新生成。",
  REVIEW_MATERIAL_INSUFFICIENT: "可用资料不足，暂时无法写出完整乐评。",
  NETWORK_UNCERTAIN:
    "连接暂时中断，操作是否完成尚不确定。请先刷新状态，或用原操作重试。",
  INVALID_RESPONSE: "服务返回了无法识别的数据，请稍后刷新状态。",
  SESSION_NOT_READY: "会话尚未连接，请重新连接。",
  UNEXPECTED_STATUS: "服务暂时不可用，请稍后刷新状态。",
  REVISION_CONFLICT: "结果已在另一页面更新，请刷新状态后再操作。",
  FORBIDDEN: "会话已失效，请刷新页面重新开始。",
  UNAUTHENTICATED: "会话已失效，请刷新页面重新开始。",
  EXPIRED: "上次发现已过期，请重新连接后开始。",
  UNDO_UNAVAILABLE: "这一步已经不能撤回了。",
};

export const GENERIC_FAILURE = "这次操作未能完成，请刷新状态后重试。";

export function message(
  key: string | null | undefined,
  fallback: string = GENERIC_FAILURE,
): string {
  if (!key) return fallback;
  const value = copy[key];
  return value === undefined || value === null ? fallback : value;
}

/** Copy for a key, or null when the key is known to need no visible note. */
export function note(key: string | null | undefined): string | null {
  if (!key) return null;
  if (key in copy) return copy[key] ?? null;
  return null;
}

export function isKnownKey(key: string | null | undefined): boolean {
  return !!key && key in copy;
}

export function stateMessage(state: ProductState): string {
  if (state.status === "WAITING")
    return "正在等待推荐名额，轮到你时会自动继续。";
  if (state.status === "RUNNING") return "正在寻找适合你的专辑…";
  if (state.analysis_mode === "BASIC")
    return "本次个性化未完成，先看看这些基础推荐。";
  return (
    {
      IDLE: "",
      SUCCEEDED: "这一组，准备好了。",
      PARTIAL: "本次找到的合格专辑不足目标数量，先听听这些。",
      NO_MATCH: "暂时没有合适的结果，试着补充更多信息。",
      FAILED: "这次推荐未能完成，请调整需求或稍后再试。",
      INTERRUPTED: "推荐已中断，已取得的结果仍可查看。",
    }[state.status] ?? ""
  );
}

export const MODE_COPY = {
  familiar: { label: "熟悉", hint: "贴近你已有的偏好" },
  explore: { label: "拓展", hint: "保留主线，探索相邻风格" },
  surprise: { label: "惊喜", hint: "允许更远的风格关联" },
} as const;
