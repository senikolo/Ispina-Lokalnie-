-- PIN-protected app password storage. Uses native OpenSSL; never stores a plaintext password.
-- Four-digit PINs are convenience locks, NOT protection against offline PIN guessing.
local ffi = require("ffi")
-- KOReader bundles its own OpenSSL and exposes it through ffi.loadlib.
-- Reuse its declarations to avoid conflicting redeclarations of EVP types.
require("ffi/crypto_h")
ffi.cdef[[
typedef struct evp_md_st EVP_MD;
const EVP_CIPHER *EVP_aes_256_gcm(void);
const EVP_MD *EVP_sha256(void);
int PKCS5_PBKDF2_HMAC(const char *pass, int passlen, const unsigned char *salt, int saltlen, int iter, const EVP_MD *digest, int keylen, unsigned char *out);
int EVP_EncryptInit_ex(EVP_CIPHER_CTX *ctx, const EVP_CIPHER *type, ENGINE *impl, const unsigned char *key, const unsigned char *iv);
int EVP_EncryptUpdate(EVP_CIPHER_CTX *ctx, unsigned char *out, int *outl, const unsigned char *in_, int inl);
int EVP_EncryptFinal_ex(EVP_CIPHER_CTX *ctx, unsigned char *out, int *outl);
int EVP_CIPHER_CTX_ctrl(EVP_CIPHER_CTX *ctx, int type, int arg, void *ptr);
]]
local lib
local ok, loaded = pcall(ffi.loadlib, "crypto", "57")
if ok then lib=loaded end
if not lib then
    for _, name in ipairs({"crypto", "libcrypto.so.3", "libcrypto.so.1.1", "libcrypto.so.1.0.0"}) do
        local success, value = pcall(ffi.load,name)
        if success then lib=value;break end
    end
end
assert(lib, "Brak biblioteki OpenSSL KOReadera.")
local Vault = {}
local ITERATIONS = 180000
local function hex(s)
    return (s:gsub(".",function(c)return string.format("%02x",c:byte())end))
end
local function unhex(s, length)
    if type(s)~="string" or #s~=length*2 or s:find("[^0-9a-f]") then return nil end
    return (s:gsub("..",function(h)return string.char(tonumber(h,16))end))
end
local function randomBytes(n)
    local f=assert(io.open("/dev/urandom","rb"),"Brak bezpiecznego generatora losowego.")
    local raw=f:read(n)
    f:close()
    assert(type(raw)=="string" and #raw==n,"Nie można pobrać losowych danych.")
    return raw
end
local function validPIN(pin)
    return type(pin)=="string" and #pin>=4 and #pin<=8 and pin:match("^%d+$")~=nil
end
Vault.validPIN=validPIN
local function ptr(s) return ffi.cast("const unsigned char*",s) end
local function keyFor(pin, salt, count)
    assert(validPIN(pin),"PIN musi mieć 4–8 cyfr.")
    local key=ffi.new("unsigned char[32]")
    assert(lib.PKCS5_PBKDF2_HMAC(pin,#pin,ptr(salt),#salt,count,lib.EVP_sha256(),32,key)==1,
        "Nie udało się przygotować klucza zabezpieczenia.")
    return key
end
local function cipher(encrypt, key, iv, bytes, tag)
    local ctx=lib.EVP_CIPHER_CTX_new()
    assert(ctx~=nil,"Nie można uruchomić szyfrowania.")
    local ok, result, auth = pcall(function()
        local first = encrypt and lib.EVP_EncryptInit_ex or lib.EVP_DecryptInit_ex
        assert(first(ctx,lib.EVP_aes_256_gcm(),nil,nil,nil)==1,"Błąd AES-GCM.")
        assert(lib.EVP_CIPHER_CTX_ctrl(ctx,0x9,#iv,nil)==1,"Błąd inicjalizacji AES-GCM.")
        assert(first(ctx,nil,nil,key,ptr(iv))==1,"Nie można przygotować szyfrowania.")
        local out=ffi.new("unsigned char[?]",#bytes+32)
        local outlen=ffi.new("int[1]")
        local updated
        if encrypt then updated=lib.EVP_EncryptUpdate(ctx,out,outlen,ptr(bytes),#bytes)
        else updated=lib.EVP_DecryptUpdate(ctx,out,outlen,ptr(bytes),#bytes) end
        assert(updated==1,"Nie można przetworzyć zapisanego hasła.")
        local length=tonumber(outlen[0])
        local final=ffi.new("int[1]")
        if encrypt then
            assert(lib.EVP_EncryptFinal_ex(ctx,out+length,final)==1,"Błąd końcowy AES-GCM.")
            local tagout=ffi.new("unsigned char[16]")
            assert(lib.EVP_CIPHER_CTX_ctrl(ctx,0x10,16,tagout)==1,"Nie można zapisać podpisu danych.")
            return ffi.string(out,length+final[0]),ffi.string(tagout,16)
        else
            local tagbytes=assert(tag,"Brak znacznika integralności.")
            local tagbuf=ffi.new("unsigned char[16]")
            ffi.copy(tagbuf,tagbytes,16)
            assert(lib.EVP_CIPHER_CTX_ctrl(ctx,0x11,16,tagbuf)==1,"Błąd zabezpieczenia danych.")
            assert(lib.EVP_DecryptFinal_ex(ctx,out+length,final)==1,"Nieprawidłowy PIN lub uszkodzone dane.")
            return ffi.string(out,length+final[0])
        end
    end)
    lib.EVP_CIPHER_CTX_free(ctx)
    if not ok then error(result,0) end
    return result, auth
end
function Vault.seal(password,pin)
    assert(type(password)=="string" and #password>0 and #password<=300,"Nieprawidłowe hasło.")
    assert(validPIN(pin),"PIN musi mieć od 4 do 8 cyfr.")
    local salt,iv=randomBytes(16),randomBytes(12)
    local key=keyFor(pin,salt,ITERATIONS)
    local encrypted,tag=cipher(true,key,iv,password)
    ffi.fill(key,32,0)
    return {version=1,rounds=ITERATIONS,salt=hex(salt),iv=hex(iv),data=hex(encrypted),tag=hex(tag)}
end
function Vault.open(saved,pin)
    if type(saved)~="table" or saved.version~=1 or not validPIN(pin) then return nil,"Zły PIN lub dane." end
    local rounds=tonumber(saved.rounds)
    if not rounds or rounds<100000 or rounds>400000 then return nil,"Zły format zapisu." end
    local salt,iv,tag=unhex(saved.salt,16),unhex(saved.iv,12),unhex(saved.tag,16)
    local bytes=type(saved.data)=="string" and #saved.data<=600 and #saved.data%2==0 and unhex(saved.data,#saved.data/2)
    if not salt or not iv or not tag or not bytes then return nil,"Uszkodzony zapis." end
    local ok,result=pcall(function()
        local key=keyFor(pin,salt,rounds)
        local value=cipher(false,key,iv,bytes,tag)
        ffi.fill(key,32,0)
        return value
    end)
    if not ok then return nil,"Błędny PIN lub uszkodzony zapis." end
    return result
end
return Vault
