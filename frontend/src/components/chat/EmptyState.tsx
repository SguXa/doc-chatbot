function EmptyState() {
  return (
    <div className="h-full flex items-center justify-center text-center">
      <div className="max-w-md px-4">
        <h2 className="text-lg font-medium mb-3">
          Ask a question about AOS documentation.
        </h2>
        <ul className="text-sm text-muted-foreground space-y-1">
          <li>What is the MA-03 error code?</li>
          <li>How do I install component X?</li>
        </ul>
      </div>
    </div>
  )
}

export { EmptyState }
