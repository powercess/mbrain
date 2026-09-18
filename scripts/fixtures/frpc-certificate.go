// Generate ephemeral certificates for the local frpc integration test, never for deployment.
package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

func main() {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		panic(err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		panic(err)
	}
	template := &x509.Certificate{
		SerialNumber: serial, Subject: pkix.Name{CommonName: "phone.example.test"},
		DNSNames:  []string{"phone.example.test", "passthrough.example.test"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour),
		IsCA: true, BasicConstraintsValid: true,
		KeyUsage:    x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
	}
	certificate, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		panic(err)
	}
	privateKey, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		panic(err)
	}
	for name, block := range map[string]*pem.Block{
		"certificate.crt": {Type: "CERTIFICATE", Bytes: certificate},
		"private.key":     {Type: "PRIVATE KEY", Bytes: privateKey},
	} {
		if err := os.WriteFile(filepath.Join(os.Args[1], name), pem.EncodeToMemory(block), 0600); err != nil {
			panic(err)
		}
	}
}
