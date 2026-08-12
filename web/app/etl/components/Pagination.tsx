"use client";

import { LeftOutlined, RightOutlined } from "@ant-design/icons";
import type { PageState } from "../model";

export function paginate<T>(rows: T[], state: PageState) {
  const pageCount = Math.max(1, Math.ceil(rows.length / state.pageSize));
  const page = Math.min(state.page, pageCount);
  return { rows: rows.slice((page - 1) * state.pageSize, page * state.pageSize), page, pageCount };
}

export function Pagination({ total, state, onChange, noun = "条" }: { total: number; state: PageState; onChange: (next: PageState) => void; noun?: string }) {
  const pageCount = Math.max(1, Math.ceil(total / state.pageSize));
  const page = Math.min(state.page, pageCount);
  const pages = Array.from({ length: Math.min(5, pageCount) }, (_, index) => {
    if (pageCount <= 5) return index + 1;
    const start = Math.min(Math.max(1, page - 2), pageCount - 4);
    return start + index;
  });
  return <div className="pagination"><span>共 {total} {noun}</span><div><button disabled={page === 1} onClick={() => onChange({ ...state, page: page - 1 })}><LeftOutlined /></button>{pages.map((value) => <button key={value} className={page === value ? "active" : ""} onClick={() => onChange({ ...state, page: value })}>{value}</button>)}<button disabled={page === pageCount} onClick={() => onChange({ ...state, page: page + 1 })}><RightOutlined /></button></div><select aria-label="每页条数" value={state.pageSize} onChange={(event) => onChange({ page: 1, pageSize: Number(event.target.value) })}><option value={5}>5 条/页</option><option value={10}>10 条/页</option><option value={20}>20 条/页</option><option value={50}>50 条/页</option></select></div>;
}
