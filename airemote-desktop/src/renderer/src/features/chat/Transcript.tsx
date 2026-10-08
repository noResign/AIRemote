import { useState } from 'react';
import { describeToolGroup, segmentBlocks, toolGroupStatus } from './segments';
import { Markdown } from './Markdown';
import type { ChatMessage, ContentBlock } from '../../store/chat/types';

type ToolBlock = Extract<ContentBlock, { kind: 'tool' }>;

const VISIBLE = 80;
const PAGE = 40;

/**
 * The message list.
 *
 * Partial virtualization by design: past a threshold only the most recent slice
 * stays mounted, with a button to reveal older turns. The plan's concern is the
 * bottom-anchored streaming scroll fighting measurement — keeping the live tail
 * small and mounted avoids that without a measurement library.
 */
export function Transcript({ messages }: { messages: ChatMessage[] }) {
  const [limit, setLimit] = useState(VISIBLE);
  const shown = messages.length > limit ? messages.slice(messages.length - limit) : messages;
  const hidden = messages.length - shown.length;

  return (
    <div className="transcript">
      {hidden > 0 && (
        <button className="btn ghost load-earlier" onClick={() => setLimit((value) => value + PAGE)}>
          显示更早的 {hidden} 条
        </button>
      )}
      {shown.map((message) =>
        message.kind === 'user' ? (
          <div key={message.id} className="msg user">
            <div className="bubble">{message.text}</div>
          </div>
        ) : (
          <AssistantRow key={message.id} message={message} />
        ),
      )}
    </div>
  );
}

function AssistantRow({ message }: { message: Extract<ChatMessage, { kind: 'assistant' }> }) {
  const segments = segmentBlocks(message.blocks);
  const [copied, setCopied] = useState(false);
  const text = message.blocks
    .filter((block): block is Extract<ContentBlock, { kind: 'text' }> => block.kind === 'text')
    .map((block) => block.text)
    .join('\n\n');

  return (
    <div className="msg assistant">
      {segments.map((segment, index) => {
        switch (segment.kind) {
          case 'text':
            return <Markdown key={index} text={segment.text} />;
          case 'thinking':
            return <ThinkingBlock key={index} text={segment.text} />;
          case 'question':
            return (
              <div key={index} className="block-question">
                <div className="block-title">需要你的回答</div>
                {segment.questions.map((question, qIndex) => (
                  <div key={qIndex} className="question">
                    <div className="question-text">{question.question}</div>
                    <div className="question-options">
                      {question.options.map((option) => (
                        <span key={option.label} className="badge">
                          {option.label}
                        </span>
                      ))}
                    </div>
                  </div>
                ))}
                <div className="hint">在桌面端回答提问属于 M2；先在手机端处理，或让 Agent 超时后重试。</div>
              </div>
            );
          case 'tools':
            return <ToolGroup key={segment.key} tools={segment.tools} />;
        }
      })}

      {message.error && <div className="msg-error">{message.error}</div>}
      {!message.done && <div className="msg-pending">● 正在执行…</div>}

      {text && (
        <div className="msg-tools">
          <button
            className="btn ghost tiny"
            onClick={() => {
              void navigator.clipboard.writeText(text);
              setCopied(true);
            }}
          >
            {copied ? '已复制' : '复制'}
          </button>
        </div>
      )}
    </div>
  );
}

function ThinkingBlock({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="block-thinking">
      <button className="fold-head" onClick={() => setOpen((value) => !value)}>
        <span className="chevron">{open ? '▾' : '▸'}</span> 思考
      </button>
      {open && <div className="thinking-body">{text}</div>}
    </div>
  );
}

/**
 * Consecutive tool calls, folded to one line. One agent turn often makes dozens
 * of calls; showing a card each would push the actual answer off screen.
 */
function ToolGroup({ tools }: { tools: ToolBlock[] }) {
  const [open, setOpen] = useState(false);
  const status = toolGroupStatus(tools);

  return (
    <div className="tool-group">
      <button className="fold-head" onClick={() => setOpen((value) => !value)}>
        <span className="chevron">{open ? '▾' : '▸'}</span>
        <span>{describeToolGroup(tools)}</span>
        {status.running > 0 && <span className="pulse" />}
        {status.failed > 0 && <span className="badge warn">✗ {status.failed}</span>}
        {status.interrupted > 0 && <span className="badge">中断 {status.interrupted}</span>}
      </button>
      {open && (
        <div className="tool-list">
          {tools.map((tool) => (
            <ToolCard key={tool.id} tool={tool} />
          ))}
        </div>
      )}
    </div>
  );
}

function ToolCard({ tool }: { tool: ToolBlock }) {
  const state = tool.running ? 'running' : tool.isError ? 'error' : tool.interrupted ? 'interrupted' : 'done';
  return (
    <details className={`tool-card ${state}`}>
      <summary>
        <span className="mono tool-name">{tool.name}</span>
        <span className="tool-state">{STATE_LABEL[state]}</span>
      </summary>
      <pre className="mono tool-io">{JSON.stringify(tool.input, null, 2)}</pre>
      {tool.result !== null && <pre className="mono tool-io result">{tool.result}</pre>}
    </details>
  );
}

const STATE_LABEL: Record<string, string> = {
  running: '运行中…',
  error: '失败',
  interrupted: '已中断',
  done: '完成',
};
