// A doc comment as HTML, for the API reference. The comments are Markdown already, their
// references to other types written as links between the reference's pages by the doclet.
import { createMarkdownProcessor } from '@astrojs/markdown-remark';

const processor = await createMarkdownProcessor();

/** `text` as HTML; `inline` for a sentence in a table cell, without a paragraph around it. */
export async function markdown(text: string, inline = false): Promise<string> {
  const html = (await processor.render(text)).code;
  return inline ? html.replace(/^<p>([\s\S]*)<\/p>\s*$/, '$1') : html;
}
