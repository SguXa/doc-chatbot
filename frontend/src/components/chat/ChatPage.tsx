function ChatPage() {
  return (
    <div className="h-screen flex">
      <aside className="w-60 shrink-0 border-r flex flex-col h-full">
        <div className="p-4 text-sm text-muted-foreground">Sidebar</div>
      </aside>
      <main className="flex-1 flex flex-col overflow-hidden">
        <div className="max-w-3xl mx-auto w-full flex-1 flex flex-col">
          <div className="flex-1" />
        </div>
      </main>
    </div>
  )
}

export { ChatPage }
