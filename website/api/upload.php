<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
function fail(int $code, string $message): never {
    http_response_code($code);
    echo json_encode(['error' => $message], JSON_UNESCAPED_UNICODE);
    exit;
}
if ($_SERVER['REQUEST_METHOD'] !== 'POST') fail(405, 'Method not allowed');
if (!isset($_FILES['file']) || !is_uploaded_file($_FILES['file']['tmp_name'])) fail(400, 'لم يتم استلام ملف');
$kind = $_POST['kind'] ?? '';
if (!in_array($kind, ['image', 'audio'], true)) fail(400, 'نوع الملف غير صحيح');
$file = $_FILES['file'];
if (($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) fail(400, 'فشل رفع الملف');
$max = $kind === 'image' ? 8 * 1024 * 1024 : 15 * 1024 * 1024;
if ((int)$file['size'] <= 0 || (int)$file['size'] > $max) fail(413, 'حجم الملف يتجاوز الحد المسموح');
$finfo = new finfo(FILEINFO_MIME_TYPE);
$mime = $finfo->file($file['tmp_name']);
$allowed = $kind === 'image'
    ? ['image/jpeg'=>'jpg', 'image/png'=>'png', 'image/webp'=>'webp', 'image/gif'=>'gif']
    : ['audio/webm'=>'webm', 'video/webm'=>'webm', 'audio/ogg'=>'ogg', 'application/ogg'=>'ogg',
       'audio/mp4'=>'m4a', 'audio/x-m4a'=>'m4a', 'audio/mpeg'=>'mp3', 'audio/wav'=>'wav',
       'audio/x-wav'=>'wav', 'audio/aac'=>'aac', 'audio/3gpp'=>'3gp'];
if (!isset($allowed[$mime])) fail(415, 'صيغة الملف غير مدعومة: ' . (string)$mime);
$folder = __DIR__ . '/../uploads/' . ($kind === 'image' ? 'images' : 'audio');
if (!is_dir($folder) && !mkdir($folder, 0755, true)) fail(500, 'تعذر إنشاء مجلد الحفظ');
$name = bin2hex(random_bytes(18)) . '.' . $allowed[$mime];
$target = $folder . '/' . $name;
if (!move_uploaded_file($file['tmp_name'], $target)) fail(500, 'تعذر حفظ الملف على الخادم');
$scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
$url = $scheme . '://' . $_SERVER['HTTP_HOST'] . '/uploads/' . ($kind === 'image' ? 'images' : 'audio') . '/' . rawurlencode($name);
echo json_encode(['ok'=>true, 'url'=>$url, 'kind'=>$kind, 'mime'=>$mime, 'size'=>(int)$file['size']], JSON_UNESCAPED_UNICODE);
