// A stand-in for <cuda_runtime.h> for scripts/ptx-check.sh: clang compiles the .cu file in CUDA mode without a CUDA toolkit
// (-nocudainc), so everything the file uses of the CUDA runtime must be declared here, with the signatures of the real API.
// Only the declarations that cuda_net.cu uses. Nothing here is linked or run; it exists so that clang can check syntax and types.
#pragma once

#include <cstddef>

#define __global__ __attribute__((global))
#define __device__ __attribute__((device))
#define __host__ __attribute__((host))
#define __shared__ __attribute__((shared))
#define __forceinline__ __inline__ __attribute__((always_inline))

struct dim3 {
    unsigned x = 1, y = 1, z = 1;
    constexpr dim3(unsigned vx = 1, unsigned vy = 1, unsigned vz = 1) : x(vx), y(vy), z(vz) {}
};
struct uint3 {
    unsigned x, y, z;
};

// Built-in variables of device code (clang's own header declares them with properties; plain constants are enough here).
extern const __attribute__((device)) uint3 threadIdx;
extern const __attribute__((device)) uint3 blockIdx;
extern const __attribute__((device)) dim3 blockDim;
extern const __attribute__((device)) dim3 gridDim;
extern "C" __attribute__((device)) void __syncthreads();
__attribute__((device)) float tanhf(float);

// Launch syntax of clang's CUDA mode without a toolkit of a known version.
extern "C" int cudaConfigureCall(dim3 grid, dim3 block, size_t shared = 0, void* stream = nullptr);
extern "C" int cudaSetupArgument(const void* arg, size_t size, size_t offset);
extern "C" int cudaLaunch(const void* func);

enum cudaError { cudaSuccess = 0, cudaErrorInvalidValue = 1 };
typedef enum cudaError cudaError_t;
enum cudaMemcpyKind { cudaMemcpyHostToHost = 0, cudaMemcpyHostToDevice = 1, cudaMemcpyDeviceToHost = 2, cudaMemcpyDeviceToDevice = 3 };
typedef struct CUstream_st* cudaStream_t;
typedef struct CUevent_st* cudaEvent_t;
#define cudaHostAllocDefault 0x00

struct cudaDeviceProp {
    char name[256];
    int major;
    int minor;
};

extern "C" {
cudaError_t cudaMalloc(void** devPtr, size_t size);
cudaError_t cudaFree(void* devPtr);
cudaError_t cudaMemset(void* devPtr, int value, size_t count);
cudaError_t cudaMemcpy(void* dst, const void* src, size_t count, enum cudaMemcpyKind kind);
cudaError_t cudaMemcpyAsync(void* dst, const void* src, size_t count, enum cudaMemcpyKind kind, cudaStream_t stream = 0);
cudaError_t cudaHostAlloc(void** pHost, size_t size, unsigned int flags);
cudaError_t cudaFreeHost(void* ptr);
cudaError_t cudaStreamCreate(cudaStream_t* pStream);
cudaError_t cudaStreamDestroy(cudaStream_t stream);
cudaError_t cudaEventCreate(cudaEvent_t* event);
cudaError_t cudaEventDestroy(cudaEvent_t event);
cudaError_t cudaEventRecord(cudaEvent_t event, cudaStream_t stream = 0);
cudaError_t cudaEventSynchronize(cudaEvent_t event);
cudaError_t cudaEventElapsedTime(float* ms, cudaEvent_t start, cudaEvent_t end);
cudaError_t cudaGetLastError(void);
const char* cudaGetErrorString(cudaError_t error);
cudaError_t cudaGetDeviceCount(int* count);
cudaError_t cudaSetDevice(int device);
cudaError_t cudaGetDeviceProperties(struct cudaDeviceProp* prop, int device);
}
