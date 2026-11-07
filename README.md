# UsbShare — দুই Android ফোনের মধ্যে USB cable দিয়ে file transfer (root লাগে না)

দুই ফোনেই একই app। একটা ফোন USB host হয়ে অন্যটাকে Android Open Accessory (AOA) mode-এ নেয়,
তারপর দুজনের মধ্যে সরাসরি USB data pipe খোলে। দুদিকেই পাঠানো যায়, folder-ও।

## ব্যবহার
1. দুই ফোনেই APK install করো, খুলে **Storage access** চাপো → "All files access" চালু করো।
2. দুই ফোন USB cable দিয়ে জোড়ো (USB-C ↔ USB-C, না হলে একদিকে OTG adapter)।
3. যে ফোন **host** হবে (cable-এ role ঠিক করে দেয়; না হলে notification → "USB controlled by" → This device),
   তার app-এ **Connect (this phone = host)** চাপো।
4. Host ফোনে "Allow USB access" আসবে → Allow। অন্য ফোনের screen unlock রাখো; ওখানে
   "Open UsbShare when this USB accessory is connected?" এলে OK দাও।
   (প্রথমবার host ফোনে ২ বার Allow লাগতে পারে।)
5. উপরে "● Connected" দেখালে হয়ে গেল।

## কীভাবে file পাঠাবে/আনবে
- **This phone** ট্যাব: নিজের ফোনের file; file/folder-এ tap (বা চেপে ধরো) → **Send to other phone**।
- **Other phone** ট্যাব: অন্য ফোনের storage; tap/চেপে ধরো → **Get**। ফোল্ডারও পুরো আসে।
- Received file: `Download/UsbShare/` (নাম মিলে গেলে `name (1).ext`)।
- যেকোনো ফোন থেকে যেকোনো দিকে চলে।

## নতুন: Media আর Recent ট্যাব
- উপরে তিনটা view: **📁 Files** (folder), **🖼 Media** (photo/video), **🕒 Recent** (সব file, নতুন থেকে পুরনো)।
- **Media**: ভিতরে *Recent* (সব photo/video, নতুন আগে, thumbnail grid) আর *Albums* (folder অনুযায়ী album, tap করলে ভিতরের photo)।
  Video-তে ▶ চিহ্ন থাকে। Grid-এ tap বা চেপে ধরলে select হয় (একসাথে অনেকগুলো), তারপর উপরের **Send N / Get N** চাপো।
- **Recent**: file-এর নাম, তারিখ-সময়, size, folder দেখায়। Tap → Send/Get; চেপে ধরলে select। Tab আবার চাপলে list নতুন করে load হয়।
- দুটোই **This phone** আর **Other phone** দুই দিকেই কাজ করে (অন্য ফোনের thumbnail-ও USB দিয়ে আসে)।
- আসা file-এর আসল তারিখ ধরে রাখা হয়, তাই photo ঠিক জায়গায় বসে। দুই ফোনেই নতুন APK লাগবে।

## নোট
- Transfer চলাকালীন app খোলা রাখো (screen চালু থাকে)।
- Connected অবস্থায় অন্য ফোন আপনার storage ব্রাউজ/নামাতে পারে — শুধু বিশ্বস্ত ফোনে লাগাও।
- Host ফোন ঠিক না হলে cable উল্টে দাও বা ফোন বদলাবদলি করো।
- গতি USB 2.0-র কাছাকাছি (প্রায় 20–40 MB/s) আশা করো।

## প্রোটোকল (Session.java)
Frame = [type u8][len u32][payload]; নতুন type: SCAN_REQ/ITEMS/END (0x13-0x15), THUMB_REQ/DATA (0x16-0x17); FILE_BEGIN-এ mtime যোগ হয়েছে; প্রতিটা frame এক USB transfer-এ যায় আর কখনো ঠিক 512 byte-এর
গুণিতকে শেষ হয় না (দরকার হলে 5 byte pad frame), কারণ USB bulk transfer short packet ছাড়া শেষ হয় না।
