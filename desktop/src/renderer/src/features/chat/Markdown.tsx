import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

/**
 * Agent output is untrusted input, so this renders to React elements rather
 * than to an HTML string.
 *
 * There is deliberately **no** `rehype-raw`: without it raw HTML in the markdown
 * is dropped instead of parsed, which is what makes this safe without a
 * sanitizer. Adding `rehype-raw` later would silently turn every agent reply
 * into an injection vector — the CSP would not save us, because the payload can
 * be plain markup and `style-src` already allows inline styles.
 */
export function Markdown({ text }: { text: string }) {
  return (
    <div className="md">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          // Links must not navigate this window: target=_blank routes through
          // the main process's window-open handler, which whitelists http(s).
          a: ({ children, href }) => (
            <a href={href} target="_blank" rel="noreferrer">
              {children}
            </a>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}
