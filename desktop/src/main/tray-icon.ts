/**
 * The tray icon, inlined as base64 rather than shipped as a binary asset: it is
 * 496 bytes, and an asset would need to survive electron-vite's bundling plus
 * electron-builder's `files` rules on three platforms for no benefit.
 *
 * A 32×32 rounded square in the brand gradient with a white "A" — generated
 * from the same two colours as `--grad` in the renderer tokens.
 */
export const TRAY_ICON_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAABt0lEQVR42s2XyW7CMBCG/4l4mlZQ4FZA6nJFArG8YnsjEe2NSxe1EE5Jl/dxD+DESWzHQ8FtLomdxJ/H88+MDfzxRaYXrfhegOQHtLtT9gQipR8AERUGLLSJsO3OyWkCzfhO0J58LHjWD8KmOyswA59wENBLFkI7AR9w+bafhKIyAV9wKjk+OIXgyMGoQRqJfAU8w9X/g7zhGb7/t4FfwLfdeebLy2TBgldX4ADLq0mFBy9pgAdXrQeATWfGgstxAhXH8rkxrbrDi3mAAY8V63tJmD2/t6fO8KIGmGq3VhMGXFkBd3jcmeXWpyEIQD+Nsr63i4kTvKQBXpxrfV7uJ7coUjTAg8OQ2+EIl+0GB75Rln8nuol2Wq+tMa4+lzVwKldDh/TK2WrVwKUXGq7wdWeaDT5II6PaX1pjAMBzc4Sbrwcr3KwBTWFxDTX7SuRwUl1QB1+3Fes/ImucX++tBoCn5sgMJ2g0YCypFd07JRkbvBgFNfXc5nMd/Pb70QGuRsEBmwliql07rpyAPDT4hK/Oh8TbFR/TcjLUst2h4fTw1dmQjEezfhIKX3Dr4XSQRuIUPv931w++VE+qIqDcgwAAAABJRU5ErkJggg==';
