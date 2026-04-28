import { useState } from 'react'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import type { Source } from '@/api/chat'

const SNIPPET_TRUNCATE_LENGTH = 150

function buildHeader(source: Source): string {
  const parts: string[] = [source.documentName]
  if (source.section !== null && source.section !== '') {
    parts.push(`§${source.section}`)
  }
  if (source.page !== null) {
    parts.push(`p.${source.page}`)
  }
  return parts.join(' · ')
}

function SourceCard({ source }: { source: Source }) {
  const [expanded, setExpanded] = useState(false)
  const isTruncatable = source.snippet.length > SNIPPET_TRUNCATE_LENGTH
  const displayText =
    !isTruncatable || expanded
      ? source.snippet
      : source.snippet.slice(0, SNIPPET_TRUNCATE_LENGTH).trimEnd() + '…'

  return (
    <Card size="sm">
      <CardHeader>
        <div className="text-sm font-medium">{buildHeader(source)}</div>
      </CardHeader>
      <CardContent>
        <p className="text-sm text-muted-foreground whitespace-pre-wrap">
          {displayText}
        </p>
        {isTruncatable && (
          <button
            type="button"
            onClick={() => setExpanded((v) => !v)}
            className="mt-2 text-sm font-medium text-primary hover:underline"
          >
            {expanded ? 'Show less' : 'Show more'}
          </button>
        )}
      </CardContent>
    </Card>
  )
}

export { SourceCard }
