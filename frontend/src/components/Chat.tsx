import { useState } from 'react';
import type { CompanyContext, Insight } from '../api/types';
import { ProvenanceLabel, type ProvKind } from '../ui/Provenance';
import { ArrowRightMIcon } from '../ui/icons';
import './Chat.css';

type Scope = 'company' | 'group';

interface Msg {
  role: 'user' | 'assistant';
  text: string;
  labels?: ProvKind[];
}

const SUGGESTIONS = [
  'Как это влияет на группу?',
  'Покажи только риски',
  'Каких данных не хватает?',
  'Почему это важно для нас?',
];

/** Уточняющий вопрос и ответы с метками происхождения (крит. 10). */
export function Chat({ ctx, insight, scope }: { ctx: CompanyContext; insight: Insight; scope: Scope }) {
  const [history, setHistory] = useState<Msg[]>([]);
  const [value, setValue] = useState('');

  const answer = (q: string): Msg => {
    const low = q.toLowerCase();
    if (/группа|групп/.test(low)) {
      const n = ctx.group.length;
      return {
        role: 'assistant',
        labels: ['ai', 'nodata'],
        text: n > 1
          ? `В группе ${n} компании. Прямой эффект события виден у участников с кредитными или лизинговыми продуктами; по остальным — через связи по платежам. Отдельный разбор на уровне группы станет доступен, когда бэкенд отдаёт агрегаты по группе.`
          : 'Группа для этой компании не указана, поэтому разрез по группе недоступен.',
      };
    }
    if (/риск/.test(low)) {
      return {
        role: 'assistant',
        labels: ['ai'],
        text: insight.risks.length ? insight.risks.map((r) => `• ${r.text}`).join('\n') : 'Явных рисков по этому событию не выделено.',
      };
    }
    if (/не хватает|данн|не знаем/.test(low)) {
      return {
        role: 'assistant',
        labels: ['nodata'],
        text: insight.missing_data.length ? insight.missing_data.map((m) => `• ${m}`).join('\n') : 'Существенных пробелов в данных не отмечено.',
      };
    }
    if (/почему|важно|влия/.test(low)) {
      return { role: 'assistant', labels: ['ai'], text: insight.ai_inference.map((a) => a.text).join('\n\n') };
    }
    return {
      role: 'assistant',
      labels: ['nodata'],
      text: 'В прототипе отвечаю на подготовленные вопросы: про группу, риски, нехватку данных и смысл события. Подключение к модели — следующий шаг.',
    };
  };

  const ask = (q: string) => {
    const text = q.trim();
    if (!text) return;
    setHistory((h) => [...h, { role: 'user', text }, answer(text)]);
    setValue('');
  };

  return (
    <section className="panel chat">
      <h2>Уточните детали</h2>
      <p className="panel__muted">
        Задайте вопрос по разбору{scope === 'group' ? ' (сейчас выбран разрез «вся группа»)' : ''}.
        Ответы приходят с метками происхождения.
      </p>

      <div className="chat__suggest">
        {SUGGESTIONS.map((s) => (
          <button key={s} className="chat__chip" onClick={() => ask(s)}>{s}</button>
        ))}
      </div>

      {history.length > 0 && (
        <div className="chat__log" aria-live="polite">
          {history.map((m, i) => (
            <div key={i} className={`chat__msg chat__msg--${m.role}`}>
              <div className="chat__who">{m.role === 'user' ? 'Вы' : 'Ответ'}</div>
              {m.labels && (
                <div className="chat__labels">{m.labels.map((l) => <ProvenanceLabel key={l} kind={l} />)}</div>
              )}
              <div className="chat__text">{m.text}</div>
            </div>
          ))}
        </div>
      )}

      <form
        className="chat__form"
        onSubmit={(e) => { e.preventDefault(); ask(value); }}
      >
        <input
          className="chat__input"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          placeholder="Спросите об этом событии…"
          aria-label="Уточняющий вопрос"
          maxLength={500}
        />
        <button className="chat__send" type="submit" aria-label="Отправить вопрос">
          <ArrowRightMIcon width={20} height={20} color="#fff" />
        </button>
      </form>
    </section>
  );
}
