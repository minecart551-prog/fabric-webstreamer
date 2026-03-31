import urllib.request

url = 'https://www.youtube.com/watch?v=82TyYH9jobk&list=PL5KDdcY-EopijbCv67o7Z8v69QLbrckJE'
req = urllib.request.Request(url, headers={
    'User-Agent': 'Mozilla/5.0',
    'Accept-Language': 'en-US,en;q=0.9'
})
body = urllib.request.urlopen(req, timeout=30).read().decode('utf-8', errors='ignore')
with open('playlist_page_snippet.txt', 'w', encoding='utf-8') as f:
    f.write(body)
print('written', len(body))
