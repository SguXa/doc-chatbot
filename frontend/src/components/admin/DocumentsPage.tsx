import { useQuery } from '@tanstack/react-query'
import { fetchDocuments } from '@/api/documents'
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card'
import { parseApiError } from '@/lib/errors'
import { DocumentTable } from './DocumentTable'

function DocumentsPage() {
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['documents'],
    queryFn: fetchDocuments,
    retry: false,
  })

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-xl font-semibold">Documents</h2>
        <p className="text-sm text-muted-foreground">
          Knowledge base documents indexed for retrieval.
        </p>
      </div>

      {isLoading && <p className="text-sm text-muted-foreground">Loading documents…</p>}

      {isError && (
        <Card className="ring-destructive/40">
          <CardHeader>
            <CardTitle className="text-destructive">Failed to load documents</CardTitle>
            <CardDescription>{parseApiError(error).message}</CardDescription>
          </CardHeader>
        </Card>
      )}

      {!isLoading && !isError && data && data.documents.length === 0 && (
        <Card>
          <CardHeader>
            <CardTitle>No documents</CardTitle>
            <CardDescription>
              No documents. Upload your first to get started.
            </CardDescription>
          </CardHeader>
        </Card>
      )}

      {!isLoading && !isError && data && data.documents.length > 0 && (
        <Card>
          <CardContent className="px-0">
            <DocumentTable documents={data.documents} />
          </CardContent>
        </Card>
      )}
    </div>
  )
}

export { DocumentsPage }
